
package com.example.gateway;

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

import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest
class QuotaPolicyTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void createSchemaAndTestData() throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE quota_policies (
                        policy_key TEXT PRIMARY KEY,
                        capacity INTEGER NOT NULL,
                        refill_rate DOUBLE PRECISION NOT NULL
                    )
                    """);

            statement.execute("""
                    INSERT INTO quota_policies
                        (policy_key, capacity, refill_rate)
                    VALUES
                        ('user:tenant_A:user_1', 3, 1.0)
                    """);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void canReadUserQuotaPolicy() {

        Map<String, Object> policy = jdbcTemplate.queryForMap(
                """
                        SELECT capacity, refill_rate
                        FROM quota_policies
                        WHERE policy_key = ?
                        """,
                "user:tenant_A:user_1"
        );

        assertEquals(3, ((Number) policy.get("capacity")).intValue());
        assertEquals(1.0, ((Number) policy.get("refill_rate")).doubleValue());
    }
}
