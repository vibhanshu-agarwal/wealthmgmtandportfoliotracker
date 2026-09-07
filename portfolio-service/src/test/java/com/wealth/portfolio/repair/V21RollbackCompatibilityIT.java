package com.wealth.portfolio.repair;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.PortfolioApplication;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.PortfolioSeedService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the accepted R-B3 application remains compatible with a database after V21 has committed.
 * The first context applies V21 and closes; the second context is the rollback-style restart and
 * must serve the ordinary read and golden-state seed paths without recreating repair helpers.
 */
@Tag("integration")
@Testcontainers
class V21RollbackCompatibilityIT {

    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @Test
    void rB3ApplicationRestartsAndServesReadAndSeedAfterV21Committed() {
        try (ConfigurableApplicationContext initial = startApplication()) {
            assertV21CommittedAndHelpersAbsent(initial.getBean(JdbcTemplate.class));
        }

        try (ConfigurableApplicationContext rollback = startApplication()) {
            JdbcTemplate jdbc = rollback.getBean(JdbcTemplate.class);
            assertV21CommittedAndHelpersAbsent(jdbc);

            PortfolioRepository portfolios = rollback.getBean(PortfolioRepository.class);
            long observedVersion =
                    portfolios.findByUserId(E2E_USER_ID).stream()
                            .findFirst()
                            .map(portfolio -> portfolio.getVersion())
                            .orElse(0L);

            PortfolioSeedService.SeedResult seeded =
                    rollback.getBean(PortfolioSeedService.class).seed(E2E_USER_ID, observedVersion);
            assertThat(seeded.holdingsInserted()).isPositive();
            assertThat(rollback.getBean(PortfolioService.class).getByUserId(E2E_USER_ID))
                    .singleElement()
                    .satisfies(
                            portfolio -> {
                                assertThat(portfolio.id()).isEqualTo(seeded.portfolioId());
                                assertThat(portfolio.holdings()).hasSize(seeded.holdingsInserted());
                            });

            assertV21CommittedAndHelpersAbsent(jdbc);
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(PortfolioApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.kafka.listener.auto-startup=false",
                        "--app.demo.seed-on-startup=false",
                        "--spring.main.banner-mode=off");
    }

    private static void assertV21CommittedAndHelpersAbsent(JdbcTemplate jdbc) {
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT COUNT(*)
                                  FROM flyway_schema_history
                                 WHERE version = '21' AND success = true
                                """,
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT COUNT(*)
                                  FROM pg_proc p
                                  JOIN pg_namespace n ON n.oid = p.pronamespace
                                 WHERE n.nspname = 'public'
                                   AND p.proname IN (
                                       'repair_archive_row',
                                       'repair_migrate_history',
                                       'repair_migrate_holdings',
                                       'repair_migrate_market_prices'
                                   )
                                """,
                                Integer.class))
                .isZero();
    }
}
