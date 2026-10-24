
package com.example.gateway;

import java.sql.DriverManager;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest
class DatabaseConnectionTest {

    private static final String TEST_API_KEY = "test-only-database-key";
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void initializeDatabase() throws Exception {

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        )) {

            // Create the table in the temporary database.
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE api_keys (
                            key_hash TEXT PRIMARY KEY,
                            active BOOLEAN NOT NULL,
                            user_id TEXT NOT NULL,
                            tenant_id TEXT NOT NULL
                        )
                        """);
            }

            // Insert exactly one test API key.
            try (var statement = connection.prepareStatement("""
                    INSERT INTO api_keys
                        (key_hash, active, user_id, tenant_id)
                    VALUES (?, ?, ?, ?)
                    """)) {

                statement.setString(1, ApiKeyHasher.hash(TEST_API_KEY));
                statement.setBoolean(2, true);
                statement.setString(3, "user_1");
                statement.setString(4, "tenant_A");
                statement.executeUpdate();
            }
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void canReadApiKeys() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_keys", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void canFindUserByApiKey() {
        String keyHash = ApiKeyHasher.hash(TEST_API_KEY);
        Map<String, Object> identity = jdbcTemplate.queryForMap(
                """
                SELECT user_id, tenant_id
                FROM api_keys
                WHERE key_hash = ?
                  AND active = true
                """,
                keyHash
        );
        assertEquals("user_1", identity.get("user_id"));
        assertEquals("tenant_A", identity.get("tenant_id"));
    }
}
