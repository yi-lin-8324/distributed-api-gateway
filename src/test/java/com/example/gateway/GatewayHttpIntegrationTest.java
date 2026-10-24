
package com.example.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHttpIntegrationTest {

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
        registry.add("upstream.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Test
    void requestWithoutApiKeyReturns401() throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/proxy/hello")).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        }
    }


    @Test
    void inactiveApiKeyReturns401() throws Exception {
        String rawKey = "integration-inactive-key";
        String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        // Prepare the temporary PostgreSQL database.
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS api_keys (
                            key_hash TEXT PRIMARY KEY,
                            active BOOLEAN NOT NULL,
                            user_id TEXT NOT NULL,
                            tenant_id TEXT NOT NULL
                        )
                        """);
            }

            try (var insert = connection.prepareStatement("""
                    INSERT INTO api_keys
                        (key_hash, active, user_id, tenant_id)
                    VALUES (?, false, 'test_user', 'test_tenant')
                    """)) {
                insert.setString(1, keyHash);
                insert.executeUpdate();
            }
        }

        // Send the inactive key to the actual gateway.
        var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/proxy/hello")).header("X-API-Key", rawKey).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        }
    }


    private static final HttpServer upstream = startUpstream();

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hello", exchange -> {
                byte[] body = "Hello from test upstream!\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }


    @Test
    void activeApiKeyReachesUpstream() throws Exception {
        String rawKey = "integration-active-key";
        String userId = "integration_user_200";
        String tenantId = "integration_tenant_200";
        String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        // Prepare our temporary PostgreSQL database.
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS api_keys (
                            key_hash TEXT PRIMARY KEY,
                            active BOOLEAN NOT NULL,
                            user_id TEXT NOT NULL,
                            tenant_id TEXT NOT NULL
                        )
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS quota_policies (
                            policy_key TEXT PRIMARY KEY,
                            capacity INTEGER NOT NULL,
                            refill_rate DOUBLE PRECISION NOT NULL
                        )
                        """);
            }
            // Seed one active API key.
            try (var insert = connection.prepareStatement("""
                    INSERT INTO api_keys
                        (key_hash, active, user_id, tenant_id)
                    VALUES (?, true, ?, ?)
                    """)) {
                insert.setString(1, keyHash);
                insert.setString(2, userId);
                insert.setString(3, tenantId);
                insert.executeUpdate();
            }
            // Seed both required quota policies.
            try (var insert = connection.prepareStatement("""
                    INSERT INTO quota_policies
                        (policy_key, capacity, refill_rate)
                    VALUES (?, 10, 0.0)
                    """)) {
                insert.setString(1, "user:" + tenantId + ":" + userId);
                insert.executeUpdate();
                insert.setString(1, "tenant:" + tenantId);
                insert.executeUpdate();
            }
        }

        // Send an authenticated request to the actual gateway.
        var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/proxy/hello")).header("X-API-Key", rawKey).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("Hello from test upstream!\n", response.body());
            // The first request already consumed one token. Consume the remaining nine tokens.
            for (int i = 0; i < 9; i++) {
                var admitted = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, admitted.statusCode());
            }
            // Both quotas are now exhausted.
            var rejected = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, rejected.statusCode());
        }
    }


}
