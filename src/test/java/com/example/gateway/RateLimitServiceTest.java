
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class RateLimitServiceTest {

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
                    CREATE TABLE IF NOT EXISTS quota_policies (
                        policy_key TEXT PRIMARY KEY,
                        capacity INTEGER NOT NULL,
                        refill_rate DOUBLE PRECISION NOT NULL
                    )
                    """);
        }
    }

    @Autowired
    private RateLimitService rateLimitService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @Transactional
    void allowsRequestAndConsumesBothTokens() {
        String userId = "test_user";
        String tenantId = "test_tenant";
        // Create temporary test quota policies.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "user:test_tenant:test_user", 3, 1.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "tenant:test_tenant", 5, 1.0);
        List<String> keys = List.of("bucket:user:test_tenant:test_user", "bucket:tenant:test_tenant");
        redisTemplate.delete(keys);
        try {
            boolean allowed = rateLimitService.checkAndConsume(userId, tenantId);
            assertTrue(allowed);
            assertEquals("2", redisTemplate.opsForHash().get(keys.get(0), "tokens"));
            assertEquals("4", redisTemplate.opsForHash().get(keys.get(1), "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @Transactional
    void rejectsWhenTenantQuotaIsExhausted() {
        String userId = "test_user_reject";
        String tenantId = "test_tenant_reject";
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "user:" + tenantId + ":" + userId, 3, 0.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "tenant:" + tenantId, 1, 0.0);
        List<String> keys = List.of("bucket:user:" + tenantId + ":" + userId, "bucket:tenant:" + tenantId);
        redisTemplate.delete(keys);
        try {
            // First request: both buckets have tokens.
            boolean first = rateLimitService.checkAndConsume(userId, tenantId);
            assertTrue(first);
            // Second request: tenant bucket is empty.
            boolean second = rateLimitService.checkAndConsume(userId, tenantId);
            assertFalse(second);
            // Rejected request did not consume user tokens.
            assertEquals("2", redisTemplate.opsForHash().get(keys.get(0), "tokens"));
            assertEquals("0", redisTemplate.opsForHash().get(keys.get(1), "tokens")
            );
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @Transactional
    void rejectsWhenUserQuotaIsExhausted() {
        String userId = "test_user_limit";
        String tenantId = "test_tenant_user_limit";
        // User has 1 token, tenant has 3.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "user:" + tenantId + ":" + userId, 1, 0.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "tenant:" + tenantId, 3, 0.0);
        List<String> keys = List.of("bucket:user:" + tenantId + ":" + userId, "bucket:tenant:" + tenantId);
        redisTemplate.delete(keys);
        try {
            boolean first = rateLimitService.checkAndConsume(userId, tenantId);
            assertTrue(first);
            boolean second = rateLimitService.checkAndConsume(userId, tenantId);
            assertFalse(second);
            // Neither bucket was consumed on rejection.
            assertEquals("0", redisTemplate.opsForHash().get(keys.get(0), "tokens"));
            assertEquals("2", redisTemplate.opsForHash().get(keys.get(1), "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @Transactional
    void twoUsersShareTheSameTenantQuota() {
        String tenantId = "shared_test_tenant";
        String userA = "shared_user_A";
        String userB = "shared_user_B";
        // Each user has their own quota.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "user:" + tenantId + ":" + userA, 2, 0.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "user:" + tenantId + ":" + userB, 2, 0.0);
        // Both users share this tenant quota.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "tenant:" + tenantId, 1, 0.0);
        String userAKey = "bucket:user:" + tenantId + ":" + userA;
        String userBKey = "bucket:user:" + tenantId + ":" + userB;
        String tenantKey = "bucket:tenant:" + tenantId;
        List<String> keys = List.of(userAKey, userBKey, tenantKey);
        redisTemplate.delete(keys);

        try {
            // Give User B a full bucket for easier verification.
            redisTemplate.opsForHash().put(userBKey, "tokens", "2");
            // User A consumes the tenant's only token.
            boolean first = rateLimitService.checkAndConsume(userA, tenantId);
            assertTrue(first);
            // User B has tokens, but the tenant is exhausted.
            boolean second = rateLimitService.checkAndConsume(userB, tenantId);
            assertFalse(second);
            assertEquals("1", redisTemplate.opsForHash().get(userAKey, "tokens"));
            assertEquals("2", redisTemplate.opsForHash().get(userBKey, "tokens"));
            assertEquals("0", redisTemplate.opsForHash().get(tenantKey, "tokens"));
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @Transactional
    void reusesCachedPolicyButStillConsumesRedisTokens() {
        String userId = "cache_test_user";
        String tenantId = "cache_test_tenant";
        String userPolicyKey = "user:" + tenantId + ":" + userId;
        // Original quota policies.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", userPolicyKey, 3, 0.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", "tenant:" + tenantId, 5, 0.0);
        String userKey = "bucket:user:" + tenantId + ":" + userId;
        String tenantKey = "bucket:tenant:" + tenantId;
        List<String> keys = List.of(userKey, tenantKey);
        redisTemplate.delete(keys);
        try {
            // First call: load policies into Caffeine.
            assertTrue(rateLimitService.checkAndConsume(userId, tenantId));
            assertEquals("2", redisTemplate.opsForHash().get(userKey, "tokens"));
            assertEquals("4", redisTemplate.opsForHash().get(tenantKey, "tokens"));
            // Change database policy to capacity 10.
            jdbcTemplate.update("UPDATE quota_policies SET capacity = ? WHERE policy_key = ?", 10, userPolicyKey);
            // Reset only the user's Redis bucket.
            redisTemplate.delete(userKey);
            // Caffeine should still have capacity 3.
            assertTrue(rateLimitService.checkAndConsume(userId, tenantId));
            // Fresh user bucket: 3 - 1 = 2, not 10 - 1 = 9.
            assertEquals("2", redisTemplate.opsForHash().get(userKey, "tokens"));
            // Tenant bucket: 4 - 1 = 3.
            // This proves Redis Lua executed again.
            assertEquals("3", redisTemplate.opsForHash().get(tenantKey, "tokens")
            );
        } finally {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @Transactional
    void secondServiceInstanceUsesRedisL2Cache() {
        String userId = "l2_test_user";
        String tenantId = "l2_test_tenant";
        String userPolicyKey = "user:" + tenantId + ":" + userId;
        String tenantPolicyKey = "tenant:" + tenantId;
        // Create test policies in PostgreSQL.
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", userPolicyKey, 3, 0.0);
        jdbcTemplate.update("INSERT INTO quota_policies VALUES (?, ?, ?)", tenantPolicyKey, 5, 0.0);
        List<String> bucketKeys = List.of("bucket:user:" + tenantId + ":" + userId, "bucket:tenant:" + tenantId);
        List<String> cacheKeys = List.of("cache:policy:" + userPolicyKey, "cache:policy:" + tenantPolicyKey);
        redisTemplate.delete(bucketKeys);
        redisTemplate.delete(cacheKeys);
        try {
            // Service A loads PostgreSQL policies into Redis L2.
            assertTrue(rateLimitService.checkAndConsume(userId, tenantId));
            // Verify Redis L2 contains the user policy.
            assertEquals("3", redisTemplate.opsForHash().get(cacheKeys.get(0), "capacity"));
            // Remove the test policies from PostgreSQL.
            jdbcTemplate.update("DELETE FROM quota_policies WHERE policy_key IN (?, ?)", userPolicyKey, tenantPolicyKey);
            // Simulate another gateway with an empty L1 cache.
            RateLimitService serviceB = new RateLimitService(jdbcTemplate, redisTemplate);
            // Service B retrieves policies from Redis L2.
            assertTrue(serviceB.checkAndConsume(userId, tenantId));
            // Both requests consumed Redis tokens.
            assertEquals("1", redisTemplate.opsForHash().get(bucketKeys.get(0), "tokens"));
            assertEquals("3", redisTemplate.opsForHash().get(bucketKeys.get(1), "tokens"));
        } finally {
            redisTemplate.delete(bucketKeys);
            redisTemplate.delete(cacheKeys);
        }
    }
}
