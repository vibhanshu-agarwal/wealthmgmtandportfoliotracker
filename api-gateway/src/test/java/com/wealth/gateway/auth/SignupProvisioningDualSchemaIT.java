package com.wealth.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.gateway.TestContainerImages;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Candidate proof for B1 Wave 2 task 2.2.
 *
 * <p>The gateway must insert a signup portfolio without naming V20-only columns, so the exact
 * repository boundary is exercised against both the pre-V20 and V20 schemas.
 */
@Tag("integration")
@Testcontainers
class SignupProvisioningDualSchemaIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)
            .withDatabaseName("portfolio_db")
            .withUsername("wealth_user")
            .withPassword("wealth_pass");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(null).clean();
    }

    @Test
    void insertPortfolioBindsUuidAsTextAgainstV19() {
        migrateTo("19");

        UUID portfolioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        repository().insertPortfolio(portfolioId, userId);

        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM portfolios WHERE id = ?", String.class, portfolioId))
                .isEqualTo(userId.toString());
    }

    @Test
    void insertPortfolioUsesV20DefaultsWithoutNamingV20Columns() {
        migrateTo("20");

        UUID portfolioId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        repository().insertPortfolio(portfolioId, userId);

        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM portfolios WHERE id = ?", String.class, portfolioId))
                .isEqualTo(userId.toString());
        assertThat(jdbc.queryForObject(
                "SELECT version FROM portfolios WHERE id = ?", Long.class, portfolioId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT updated_at IS NOT NULL FROM portfolios WHERE id = ?", Boolean.class, portfolioId))
                .isTrue();
    }

    private UserCredentialRepository repository() {
        return new UserCredentialRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    private void migrateTo(String target) {
        flyway(target).migrate();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:../portfolio-service/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
