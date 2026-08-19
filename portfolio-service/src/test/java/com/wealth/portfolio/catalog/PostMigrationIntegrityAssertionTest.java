package com.wealth.portfolio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.repair.PostgresRepairHarness;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Direct checks of {@link PostMigrationIntegrityAssertion} against fixtures, without
 * running V17–V19. Covers the V18/V19 leftover-ticker postconditions and the
 * created-vs-pre-existing deprecated distinction.
 */
@Tag("integration")
class PostMigrationIntegrityAssertionTest {

    @BeforeAll
    static void startContainer() {
        PostgresRepairHarness.POSTGRES.start();
    }

    @Test
    void v18PostconditionFailsOnLeftoverBtc() {
        JdbcTemplate jdbc = fixture();
        insertHolding(jdbc, "BTC-USD");
        insertPrice(jdbc, "BTC");
        List<String> violations = PostMigrationIntegrityAssertion.assertV18Postconditions(jdbc);
        assertThat(violations).anyMatch(v -> v.contains("market_prices still contains BTC"));
    }

    @Test
    void v18PostconditionPassesWhenBtcGone() {
        JdbcTemplate jdbc = fixture();
        insertHolding(jdbc, "BTC-USD");
        insertPrice(jdbc, "BTC-USD");
        assertThat(PostMigrationIntegrityAssertion.assertV18Postconditions(jdbc)).isEmpty();
    }

    @Test
    void v19PostconditionFailsOnLeftoverMmNs() {
        JdbcTemplate jdbc = fixture();
        jdbc.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) "
                        + "VALUES ('MM.NS', 'INR', 1, now())");
        List<String> violations = PostMigrationIntegrityAssertion.assertV19Postconditions(jdbc);
        assertThat(violations).anyMatch(v -> v.contains("market_price_history still contains MM.NS"));
    }

    @Test
    void v19PostconditionPassesWhenMmNsGone() {
        JdbcTemplate jdbc = fixture();
        jdbc.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) "
                        + "VALUES ('M&M.NS', 'INR', 1, now())");
        assertThat(PostMigrationIntegrityAssertion.assertV19Postconditions(jdbc)).isEmpty();
    }

    @Test
    void auditCreatedDeprecatedPositionFails_preExistingDeprecatedPasses() {
        JdbcTemplate jdbc = fixture();
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolios (id, user_id) VALUES (?, 'assert-it')", portfolioId);
        jdbc.update(
                """
                INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
                VALUES (?, 'TATAMOTORS.NS', 1)
                """,
                portfolioId);

        SupportedCatalog catalog = SupportedCatalog.load();
        PostMigrationIntegrityAssertion.assertSatisfied(jdbc, catalog);

        jdbc.update(
                """
                INSERT INTO repair_audit (migration_version, portfolio_id, asset_ticker, action)
                VALUES ('V19', ?, 'TATAMOTORS.NS', 'CREATED')
                """,
                portfolioId);
        assertThatThrownBy(() -> PostMigrationIntegrityAssertion.assertSatisfied(jdbc, catalog))
                .isInstanceOf(PostMigrationIntegrityFailedException.class)
                .hasMessageContaining("TATAMOTORS.NS");
    }

    @Test
    void referentialInvariantFailsForUnknownTicker() {
        JdbcTemplate jdbc = fixture();
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolios (id, user_id) VALUES (?, 'assert-it')", portfolioId);
        jdbc.update(
                "INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity) VALUES (?, 'NOT_A_TICKER', 1)",
                portfolioId);
        List<String> violations =
                PostMigrationIntegrityAssertion.assertReferentialInvariant(jdbc, SupportedCatalog.load());
        assertThat(violations).anyMatch(v -> v.contains("NOT_A_TICKER"));
    }

    private static JdbcTemplate fixture() {
        PostgresRepairHarness.Session session = PostgresRepairHarness.newSession();
        JdbcTemplate jdbc = session.jdbc();
        jdbc.execute(
                """
                CREATE TABLE portfolios (
                    id UUID PRIMARY KEY,
                    user_id VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE asset_holdings (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    portfolio_id UUID NOT NULL REFERENCES portfolios (id),
                    asset_ticker VARCHAR(20) NOT NULL,
                    quantity NUMERIC(19, 8) NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE market_prices (
                    ticker VARCHAR(20) PRIMARY KEY,
                    current_price NUMERIC(19, 4) NOT NULL,
                    quote_currency VARCHAR(10) NOT NULL,
                    observed_at TIMESTAMP(3) NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE market_price_history (
                    id BIGSERIAL PRIMARY KEY,
                    ticker VARCHAR(20) NOT NULL,
                    quote_currency VARCHAR(10) NOT NULL,
                    price NUMERIC(19, 4) NOT NULL,
                    observed_at TIMESTAMP(3) NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE repair_archive (
                    id BIGSERIAL PRIMARY KEY,
                    migration_version VARCHAR(16) NOT NULL,
                    source_table VARCHAR(64) NOT NULL,
                    reason VARCHAR(32) NOT NULL,
                    natural_key TEXT NOT NULL,
                    payload JSONB NOT NULL,
                    archived_at TIMESTAMP(3) NOT NULL DEFAULT clock_timestamp()
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE repair_audit (
                    migration_version VARCHAR(16) NOT NULL,
                    portfolio_id UUID NOT NULL,
                    asset_ticker VARCHAR(20) NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    recorded_at TIMESTAMP(3) NOT NULL DEFAULT clock_timestamp(),
                    PRIMARY KEY (migration_version, portfolio_id, asset_ticker)
                )
                """);
        return jdbc;
    }

    private static void insertHolding(JdbcTemplate jdbc, String ticker) {
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolios (id, user_id) VALUES (?, 'assert-it')", portfolioId);
        jdbc.update(
                "INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity) VALUES (?, ?, 1)",
                portfolioId,
                ticker);
    }

    private static void insertPrice(JdbcTemplate jdbc, String ticker) {
        jdbc.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at) VALUES (?, 1, 'USD', now())",
                ticker);
    }
}
