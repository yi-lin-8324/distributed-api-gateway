
package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisContainerTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Test
    void redisRespondsToPing() throws Exception {
        var result = redis.execInContainer("redis-cli", "PING");
        assertEquals(0, result.getExitCode());
        assertEquals("PONG", result.getStdout().trim());
    }


    @Test
    void tokenBucketScriptLoadsSuccessfully() throws Exception {
        try (var stream = RedisContainerTest.class.getResourceAsStream("/token_bucket.lua")) {
            assertNotNull(stream);
            String lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var result = redis.execInContainer("redis-cli", "SCRIPT", "LOAD", lua);
            assertEquals(0, result.getExitCode(), result.getStderr());
            String scriptHash = result.getStdout().trim();
            assertTrue(scriptHash.matches("[0-9a-f]{40}"));
        }
    }


    @Test
    void newBucketsAllowFirstRequest() throws Exception {
        String lua;
        try (var stream = RedisContainerTest.class.getResourceAsStream("/token_bucket.lua")) {
            assertNotNull(stream);
            lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var result = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                "bucket:user:test_tenant:test_user",
                "bucket:tenant:test_tenant",
                "3", "1",
                "5", "1"
        );
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertEquals("1", result.getStdout().trim());
    }


    @Test
    void exhaustedUserBucketRejectsSecondRequest() throws Exception {
        String lua;
        try (var stream = RedisContainerTest.class.getResourceAsStream("/token_bucket.lua")) {
            assertNotNull(stream);
            lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String userKey = "bucket:user:reject_tenant:reject_user";
        String tenantKey = "bucket:tenant:reject_tenant";
        var first = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userKey, tenantKey,
                "1", "0",
                "2", "0"
        );
        assertEquals(0, first.getExitCode(), first.getStderr());
        assertEquals("1", first.getStdout().trim());
        var second = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userKey, tenantKey,
                "1", "0",
                "2", "0"
        );
        assertEquals(0, second.getExitCode(), second.getStderr());
        assertEquals("0", second.getStdout().trim());
    }


    @Test
    void rejectedRequestDoesNotConsumeTenantToken() throws Exception {
        String lua;
        try (var stream = RedisContainerTest.class.getResourceAsStream("/token_bucket.lua")) {
            assertNotNull(stream);
            lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String tenantKey = "bucket:tenant:atomic_test";
        String userAKey = "bucket:user:atomic_test:user_A";
        String userBKey = "bucket:user:atomic_test:user_B";
        var first = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userAKey, tenantKey,
                "1", "0",
                "2", "0"
        );
        assertEquals(0, first.getExitCode(), first.getStderr());
        assertEquals("1", first.getStdout().trim());
        var rejected = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userAKey, tenantKey,
                "1", "0",
                "2", "0"
        );
        assertEquals(0, rejected.getExitCode(), rejected.getStderr());
        assertEquals("0", rejected.getStdout().trim());
        var otherUser = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userBKey, tenantKey,
                "1", "0",
                "2", "0"
        );
        assertEquals(0, otherUser.getExitCode(), otherUser.getStderr());
        assertEquals("1", otherUser.getStdout().trim());
    }


    @Test
    void exhaustedTenantBucketRejectsAnotherUser() throws Exception {
        String lua;
        try (var stream = RedisContainerTest.class.getResourceAsStream("/token_bucket.lua")) {
            assertNotNull(stream);
            lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String tenantKey = "bucket:tenant:shared_limit_test";
        String userAKey = "bucket:user:shared_limit_test:user_A";
        String userBKey = "bucket:user:shared_limit_test:user_B";
        var userA = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userAKey, tenantKey,
                "2", "0",
                "1", "0"
        );
        assertEquals(0, userA.getExitCode(), userA.getStderr());
        assertEquals("1", userA.getStdout().trim());
        var userB = redis.execInContainer(
                "redis-cli", "--raw",
                "EVAL", lua, "2",
                userBKey, tenantKey,
                "2", "0",
                "1", "0"
        );
        assertEquals(0, userB.getExitCode(), userB.getStderr());
        assertEquals("0", userB.getStdout().trim());
    }


}
