
package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresContainerTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Test
    void postgresAcceptsSqlQueries() throws Exception {
        try (
                var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT 1")
        ) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }


    @Test
    void activeApiKeyReturnsUserAndTenant() throws Exception {
        String testKeyHash = "a".repeat(64);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS api_keys (key_hash TEXT PRIMARY KEY,active BOOLEAN NOT NULL,user_id TEXT NOT NULL,tenant_id TEXT NOT NULL)");
            }

            try (var insert = connection.prepareStatement("INSERT INTO api_keys (key_hash, active, user_id, tenant_id) VALUES (?, ?, ?, ?) ")) {
                insert.setString(1, testKeyHash);
                insert.setBoolean(2, true);
                insert.setString(3, "user_1");
                insert.setString(4, "tenant_A");
                insert.executeUpdate();
            }

            try (var lookup = connection.prepareStatement("SELECT user_id, tenant_id FROM api_keys WHERE key_hash = ? AND active = true")) {
                lookup.setString(1, testKeyHash);
                try (var result = lookup.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("user_1", result.getString("user_id"));
                    assertEquals("tenant_A", result.getString("tenant_id"));
                }
            }
        }
    }

    @Test
    void inactiveApiKeyIsRejected() throws Exception {
        String testKeyHash = "b".repeat(64);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS api_keys (key_hash TEXT PRIMARY KEY, active BOOLEAN NOT NULL, user_id TEXT NOT NULL, tenant_id TEXT NOT NULL)");
            }

            try (var insert = connection.prepareStatement("INSERT INTO api_keys (key_hash, active, user_id, tenant_id) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, testKeyHash);
                insert.setBoolean(2, false);
                insert.setString(3, "user_2");
                insert.setString(4, "tenant_A");
                insert.executeUpdate();
            }
            try (var lookup = connection.prepareStatement("SELECT user_id, tenant_id FROM api_keys WHERE key_hash = ? AND active = true")) {
                lookup.setString(1, testKeyHash);
                try (var result = lookup.executeQuery()) {
                    assertFalse(result.next());
                }
            }
        }
    }

}
