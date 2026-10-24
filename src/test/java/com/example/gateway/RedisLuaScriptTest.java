
package com.example.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest
class RedisLuaScriptTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeAll
    static void createSchemaAndPolicies() throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quota_policies (
                        policy_key TEXT PRIMARY KEY,
                        capacity INTEGER NOT NULL,
                        refill_rate NUMERIC NOT NULL
                    )
                    """);

            statement.execute("""
                    INSERT INTO quota_policies
                        (policy_key, capacity, refill_rate)
                    VALUES
                        ('user:tenant_A:user_1', 3, 1),
                        ('tenant:tenant_A', 5, 0.5)
                    """);
        }
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void executesScriptUsingDatabasePolicies() {
        String userId = "user_1";
        String tenantId = "tenant_A";
        // Read both policies from PostgreSQL.
        Map<String, Object> userPolicy = jdbcTemplate.queryForMap(
                """
                        SELECT capacity, refill_rate
                        FROM quota_policies
                        WHERE policy_key = ?
                        """,
                "user:" + tenantId + ":" + userId
        );
        Map<String, Object> tenantPolicy = jdbcTemplate.queryForMap(
                """
                        SELECT capacity, refill_rate
                        FROM quota_policies
                        WHERE policy_key = ?
                        """,
                "tenant:" + tenantId
        );
        // Build Redis bucket keys.
        List<String> keys = List.of("learning:bucket:user:" + tenantId + ":" + userId, "learning:bucket:tenant:" + tenantId);
        RedisScript<Long> script = RedisScript.of(new ClassPathResource("token_bucket.lua"), Long.class);
        redisTemplate.delete(keys);
        try {
            // Execute Lua using PostgreSQL policy values.
            Long result = redisTemplate.execute(
                    script,
                    keys,
                    userPolicy.get("capacity").toString(),
                    userPolicy.get("refill_rate").toString(),
                    tenantPolicy.get("capacity").toString(),
                    tenantPolicy.get("refill_rate").toString()
            );
            assertEquals(1L, result);
            assertEquals("2", redisTemplate.opsForHash().get(keys.get(0), "tokens"));
            assertEquals("4", redisTemplate.opsForHash().get(keys.get(1), "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void calculatesRetryDelayWhenBothBucketsAreEmpty() {
        List<String> keys = List.of("learning:retry:user", "learning:retry:tenant");
        RedisScript<Long> script = RedisScript.of(new ClassPathResource("token_bucket.lua"), Long.class);
        redisTemplate.delete(keys);
        try {
            // Start both buckets empty.
            redisTemplate.opsForHash().put(keys.get(0), "tokens", "0");
            redisTemplate.opsForHash().put(keys.get(1), "tokens", "0");
            // User refills at 1 token/sec.
            // Tenant refills at 0.5 tokens/sec.
            Long result = redisTemplate.execute(script, keys, "3", "1", "5", "0.5");
            // Tenant needs 2 seconds, so Lua returns -2.
            assertEquals(-2L, result);
        } finally {
            redisTemplate.delete(keys);
        }
    }
}
