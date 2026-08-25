package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Integration proof for B1 Wave 3 task 3.1's V20 schema transition. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class V20MigrationIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)
            .withDatabaseName("portfolio_db")
            .withUsername("wealth_user")
            .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void v20BackfillsExactlyOnePortfolioForEveryUser() {
        Integer violations = jdbc.queryForObject("""
                SELECT count(*)
                  FROM (
                    SELECT u.id
                      FROM users u
                      LEFT JOIN portfolios p ON p.user_id = u.id::text
                     GROUP BY u.id
                    HAVING count(p.id) <> 1
                  ) invariant_violations
                """, Integer.class);

        assertThat(violations).isZero();
    }

    @Test
    void v20AddsDefaultsAndNamedConstraintsForPortfolioComposition() {
        UUID userId = UUID.randomUUID();
        UUID firstPortfolioId = UUID.randomUUID();
        UUID secondPortfolioId = UUID.randomUUID();

        jdbc.update("INSERT INTO portfolios (id, user_id) VALUES (?, ?)", firstPortfolioId, userId.toString());

        assertThat(jdbc.queryForObject(
                "SELECT version FROM portfolios WHERE id = ?", Long.class, firstPortfolioId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT updated_at IS NOT NULL FROM portfolios WHERE id = ?", Boolean.class, firstPortfolioId))
                .isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'portfolios'::regclass
                   AND conname = 'uq_portfolios_user_id'
                """, Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO portfolios (id, user_id) VALUES (?, ?)", secondPortfolioId, userId.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'asset_holdings'::regclass
                   AND conname = 'chk_asset_holdings_quantity_positive'
                """, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
                VALUES (?, 'V20_NONPOSITIVE', 0)
                """, firstPortfolioId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
