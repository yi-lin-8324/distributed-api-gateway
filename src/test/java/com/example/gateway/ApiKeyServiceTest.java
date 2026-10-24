
package com.example.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

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
class ApiKeyServiceTest {

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
    static void createSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS api_keys (
                        key_hash TEXT PRIMARY KEY,
                        active BOOLEAN NOT NULL,
                        user_id TEXT NOT NULL,
                        tenant_id TEXT NOT NULL
                    )
                    """);
        }
    }
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @Transactional
    void findsActiveApiKeyIdentity() {
        String apiKey = "test-only-identity-key";
        String keyHash = ApiKeyHasher.hash(apiKey);
        jdbcTemplate.update(
                """
                INSERT INTO api_keys
                    (key_hash, active, user_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """,
                keyHash,
                true,
                "identity_test_user",
                "identity_test_tenant"
        );
        List<Map<String, Object>> matches = apiKeyService.findActiveIdentity(keyHash);
        assertEquals(1, matches.size());
        assertEquals("identity_test_user", matches.get(0).get("user_id"));
        assertEquals("identity_test_tenant", matches.get(0).get("tenant_id"));
    }

    @Test
    @Transactional
    void reusesCachedApiKeyIdentity() {
        String apiKey = "test-only-identity-cache-key";
        String keyHash = ApiKeyHasher.hash(apiKey);
        // Create an active API key.
        jdbcTemplate.update(
                """
                INSERT INTO api_keys
                    (key_hash, active, user_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """,
                keyHash,
                true,
                "original_user",
                "cache_test_tenant"
        );
        // First lookup: Caffeine miss, PostgreSQL hit.
        List<Map<String, Object>> first = apiKeyService.findActiveIdentity(keyHash);
        assertEquals("original_user", first.get(0).get("user_id"));
        // Change the identity in PostgreSQL.
        jdbcTemplate.update(
                "UPDATE api_keys SET user_id = ? WHERE key_hash = ?",
                "changed_user",
                keyHash
        );
        // Second lookup: Caffeine hit.
        List<Map<String, Object>> second = apiKeyService.findActiveIdentity(keyHash);
        // Still returns the cached identity.
        assertEquals("original_user", second.get(0).get("user_id"));
    }

    @Test
    @Transactional
    void secondServiceInstanceUsesRedisL2IdentityCache() {
        String apiKey = "test-only-identity-l2-key";
        String keyHash = ApiKeyHasher.hash(apiKey);
        String redisKey = "cache:identity:" + keyHash;
        // Create a temporary active API key.
        jdbcTemplate.update(
                """
                INSERT INTO api_keys
                    (key_hash, active, user_id, tenant_id)
                VALUES (?, ?, ?, ?)
                """,
                keyHash,
                true,
                "l2_identity_user",
                "l2_identity_tenant"
        );
        redisTemplate.delete(redisKey);
        try {
            // Service A starts with an empty Caffeine L1.
            ApiKeyService serviceA = new ApiKeyService(jdbcTemplate, redisTemplate);
            // First lookup: PostgreSQL supplies the identity.
            List<Map<String, Object>> first = serviceA.findActiveIdentity(keyHash);
            assertEquals(1, first.size());
            // Verify Service A populated Redis L2.
            assertEquals("l2_identity_user", redisTemplate.opsForHash().get(redisKey, "user_id")
            );
            // Temporarily delete the PostgreSQL record.
            jdbcTemplate.update("DELETE FROM api_keys WHERE key_hash = ?", keyHash);
            // Service B has its own empty Caffeine L1.
            ApiKeyService serviceB = new ApiKeyService(jdbcTemplate, redisTemplate);
            // Service B must get the identity from Redis L2.
            List<Map<String, Object>> second = serviceB.findActiveIdentity(keyHash);
            assertEquals(1, second.size());
            assertEquals("l2_identity_user", second.get(0).get("user_id"));
            assertEquals("l2_identity_tenant", second.get(0).get("tenant_id"));
        } finally {
            redisTemplate.delete(redisKey);
        }
    }
}
