
package com.example.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProxyControllerTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    private static final HttpServer upstream = startUpstream();
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureTestEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("upstream.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (
                var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                );
                var statement = connection.createStatement()
        ) {
            statement.execute("""
                    CREATE TABLE api_keys (
                        key_hash TEXT PRIMARY KEY,
                        active BOOLEAN NOT NULL,
                        user_id TEXT NOT NULL,
                        tenant_id TEXT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE quota_policies (
                        policy_key TEXT PRIMARY KEY,
                        capacity INTEGER NOT NULL,
                        refill_rate DOUBLE PRECISION NOT NULL
                    )
                    """);
        }
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hello", exchange -> {
                byte[] body = "Hello from upstream!\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start test upstream", e);
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    private record TestIdentity(
            String apiKey,
            String userId,
            String tenantId
    ) {
    }

    private TestIdentity createIdentity(
            boolean active,
            int userCapacity,
            double userRefill,
            int tenantCapacity,
            double tenantRefill
    ) {
        String id = UUID.randomUUID().toString();
        String apiKey = "test-key-" + id;
        String userId = "user_" + id;
        String tenantId = "tenant_" + id;

        jdbcTemplate.update("""
                        INSERT INTO api_keys
                            (key_hash, active, user_id, tenant_id)
                        VALUES (?, ?, ?, ?)
                        """,
                ApiKeyHasher.hash(apiKey),
                active,
                userId,
                tenantId
        );

        jdbcTemplate.update("""
                        INSERT INTO quota_policies
                            (policy_key, capacity, refill_rate)
                        VALUES (?, ?, ?)
                        """,
                "user:" + tenantId + ":" + userId,
                userCapacity,
                userRefill
        );

        jdbcTemplate.update("""
                        INSERT INTO quota_policies
                            (policy_key, capacity, refill_rate)
                        VALUES (?, ?, ?)
                        """,
                "tenant:" + tenantId,
                tenantCapacity,
                tenantRefill
        );
        return new TestIdentity(apiKey, userId, tenantId);
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(get("/proxy/hello")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestWithInvalidApiKey() throws Exception {
        mockMvc.perform(get("/proxy/hello").header("X-API-Key", "wrong-key")).andExpect(status().isUnauthorized());
    }

    @Test
    void forwardsRequestWithValidApiKey() throws Exception {
        var identity = createIdentity(true, 3, 1.0, 5, 1.0);
        mockMvc.perform(get("/proxy/hello").header("X-API-Key", identity.apiKey()))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello from upstream!\n"));
    }

    @Test
    void rejectsInactiveApiKey() throws Exception {
        var identity = createIdentity(true, 3, 0.0, 5, 0.0);
        int updatedRows = jdbcTemplate.update("UPDATE api_keys SET active = false WHERE key_hash = ?", ApiKeyHasher.hash(identity.apiKey()));
        assertEquals(1, updatedRows);
        mockMvc.perform(get("/proxy/hello").header("X-API-Key", identity.apiKey())).andExpect(status().isUnauthorized());
    }

    @Test
    void returns429WhenUserQuotaIsExhausted() throws Exception {
        var identity = createIdentity(true, 1, 0.0, 5, 0.0);
        String userKey = "bucket:user:" + identity.tenantId() + ":" + identity.userId();
        String tenantKey = "bucket:tenant:" + identity.tenantId();
        List<String> keys = List.of(userKey, tenantKey);
        try {
            redisTemplate.opsForHash().put(userKey, "tokens", "0");
            redisTemplate.opsForHash().put(tenantKey, "tokens", "2");
            mockMvc.perform(get("/proxy/hello").header("X-API-Key", identity.apiKey())).andExpect(status().isTooManyRequests());
            assertEquals("0", redisTemplate.opsForHash().get(userKey, "tokens"));
            assertEquals("2", redisTemplate.opsForHash().get(tenantKey, "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void returns429WhenTenantQuotaIsExhausted() throws Exception {
        var identity = createIdentity(true, 3, 0.0, 1, 0.0);
        String userKey = "bucket:user:" + identity.tenantId() + ":" + identity.userId();
        String tenantKey = "bucket:tenant:" + identity.tenantId();
        List<String> keys = List.of(userKey, tenantKey);
        try {
            redisTemplate.opsForHash().put(userKey, "tokens", "2");
            redisTemplate.opsForHash().put(tenantKey, "tokens", "0");
            mockMvc.perform(get("/proxy/hello").header("X-API-Key", identity.apiKey())).andExpect(status().isTooManyRequests());
            assertEquals("2", redisTemplate.opsForHash().get(userKey, "tokens"));
            assertEquals("0", redisTemplate.opsForHash().get(tenantKey, "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void returnsRetryAfterHeaderWhenUserQuotaIsExhausted() throws Exception {
        var identity = createIdentity(true, 3, 0.5, 5, 0.0);
        String userKey = "bucket:user:" + identity.tenantId() + ":" + identity.userId();
        String tenantKey = "bucket:tenant:" + identity.tenantId();
        List<String> keys = List.of(userKey, tenantKey);
        try {
            redisTemplate.opsForHash().put(userKey, "tokens", "0");
            redisTemplate.opsForHash().put(tenantKey, "tokens", "2");
            mockMvc.perform(get("/proxy/hello").header("X-API-Key", identity.apiKey()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "2"));
        } finally {
            redisTemplate.delete(keys);
        }
    }
}
