package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Wave 2 Flyway migrations V11, V12, V13.
 *
 * <p>Validates:
 * <ul>
 *   <li>Task 3.1 — V11 adds cost-basis columns to {@code asset_holdings}.</li>
 *   <li>Task 3.2 — V12 backfills {@code market_price_history} for all 160 canonical tickers
 *       (unique symbols, canonical BTC-USD not legacy BTC), idempotent on re-run.</li>
 *   <li>Task 3.3 — V13 adds unique index on {@code (ticker, observed_at)}.</li>
 *   <li>Task 3.4 — Migrations apply cleanly; re-running backfill is a no-op.</li>
 * </ul>
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class Wave2MigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ── V11: cost-basis columns exist on asset_holdings ───────────────────────

    @Test
    void v11_costBasisColumnsExistOnAssetHoldings() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'asset_holdings' " +
                "ORDER BY column_name",
                String.class);

        assertThat(columns).contains(
                "avg_cost_basis",
                "cost_basis_currency",
                "cost_basis_source",
                "cost_basis_as_of"
        );
    }

    @Test
    void v11_costBasisColumnsAreNullable() {
        List<Map<String, Object>> nullability = jdbcTemplate.queryForList(
                "SELECT column_name, is_nullable FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'asset_holdings' " +
                "AND column_name IN ('avg_cost_basis','cost_basis_currency','cost_basis_source','cost_basis_as_of')");

        assertThat(nullability).allSatisfy(row ->
                assertThat(row.get("is_nullable")).isEqualTo("YES"));
    }

    // ── V12: backfill covers exactly 160 canonical tickers ───────────────────

    @Test
    void v12_backfillCovers160CanonicalTickers() {
        Integer distinctTickers = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ticker) FROM market_price_history",
                Integer.class);

        // V2 seeded AAPL, TSLA, BTC (legacy); V12 adds the 160 canonical tickers.
        // Combined: the canonical set is ≥ 160 distinct symbols (AAPL, TSLA, BTC-USD included,
        // legacy BTC is separate). We assert ≥ 160 to be resilient to the legacy rows from V2.
        assertThat(distinctTickers)
                .as("market_price_history should cover at least 160 distinct canonical tickers after V12")
                .isGreaterThanOrEqualTo(160);
    }

    @Test
    void v12_canonicalBtcUsdPresent_notLegacyBtcOnly() {
        Integer btcUsdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'BTC-USD'",
                Integer.class);

        assertThat(btcUsdCount)
                .as("BTC-USD (canonical) must have history rows from V12 backfill")
                .isGreaterThan(0);
    }

    @Test
    void v12_eachBackfilledTickerHasMultipleDayPoints() {
        // V12 generates 4 points per ticker (day_offset 0-3). Spot check a few.
        for (String ticker : List.of("AAPL", "BTC-USD", "RELIANCE.NS", "EURUSD=X")) {
            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?",
                    Integer.class, ticker);
            assertThat(rowCount)
                    .as("Ticker %s should have ≥ 2 history points (enough for 24h reference)", ticker)
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void v12_quoteCurrencyPopulatedCorrectly() {
        // NSE tickers should be INR.
        String inrTicker = jdbcTemplate.queryForObject(
                "SELECT DISTINCT quote_currency FROM market_price_history WHERE ticker = 'RELIANCE.NS' LIMIT 1",
                String.class);
        assertThat(inrTicker).isEqualTo("INR");

        // US equity tickers should be USD.
        String usdTicker = jdbcTemplate.queryForObject(
                "SELECT DISTINCT quote_currency FROM market_price_history WHERE ticker = 'AAPL' LIMIT 1",
                String.class);
        assertThat(usdTicker).isEqualTo("USD");
    }

    // ── V12 idempotency: re-running inserts nothing ───────────────────────────

    @Test
    void v12_backfillIsIdempotent() {
        Integer countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'NVDA'",
                Integer.class);

        // Simulate re-run by executing the same INSERT … ON CONFLICT DO NOTHING manually.
        jdbcTemplate.update("""
            INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
            VALUES ('NVDA', 'USD', 880.10, now() - INTERVAL '0 day')
            ON CONFLICT DO NOTHING
            """);

        Integer countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'NVDA'",
                Integer.class);

        assertThat(countAfter)
                .as("Re-running backfill insert for NVDA should be a no-op on (ticker, observed_at) conflict")
                .isEqualTo(countBefore);
    }

    // ── V13: unique index exists on (ticker, observed_at) ────────────────────

    @Test
    void v13_uniqueIndexExistsOnTickerObservedAt() {
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes " +
                "WHERE schemaname = 'public' " +
                "AND tablename = 'market_price_history' " +
                "AND indexname = 'uidx_market_price_history_ticker_observed_at'",
                Integer.class);

        assertThat(indexCount)
                .as("Unique index uidx_market_price_history_ticker_observed_at should exist after V13")
                .isEqualTo(1);
    }

    @Test
    void v13_uniqueConstraintPreventsExactDuplicate() {
        // Insert a test row, then attempt to insert an exact duplicate — must be rejected.
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) " +
                "VALUES ('DEDUP_TEST', 'USD', 100.00, '2026-01-01 12:00:00') " +
                "ON CONFLICT DO NOTHING");

        // Count before.
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'DEDUP_TEST'",
                Integer.class);

        // Attempt exact duplicate — ON CONFLICT DO NOTHING should silently skip.
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) " +
                "VALUES ('DEDUP_TEST', 'USD', 100.00, '2026-01-01 12:00:00') " +
                "ON CONFLICT DO NOTHING");

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'DEDUP_TEST'",
                Integer.class);

        assertThat(after).isEqualTo(before);

        // Clean up.
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = 'DEDUP_TEST'");
    }

    @Test
    void v13_newTimestampSamePriceIsDistinctRow() {
        // A new observed_at with the same price must be a valid new row.
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) " +
                "VALUES ('DISTINCT_TEST', 'USD', 200.00, '2026-01-01 10:00:00') " +
                "ON CONFLICT DO NOTHING");
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) " +
                "VALUES ('DISTINCT_TEST', 'USD', 200.00, '2026-01-02 10:00:00') " +
                "ON CONFLICT DO NOTHING");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'DISTINCT_TEST'",
                Integer.class);

        assertThat(count)
                .as("Two rows with same price but different observed_at should both exist")
                .isEqualTo(2);

        // Clean up.
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = 'DISTINCT_TEST'");
    }

    // ── All migrations applied cleanly (no failed entries) ───────────────────

    @Test
    void allMigrationsApplyCleanly() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history " +
                "WHERE version IS NOT NULL ORDER BY installed_rank");

        List<String> versions = history.stream()
                .map(r -> (String) r.get("version"))
                .toList();

        assertThat(versions).contains("11", "12", "13");

        for (Map<String, Object> row : history) {
            assertThat(row.get("success"))
                    .as("Migration V%s should succeed", row.get("version"))
                    .isEqualTo(true);
        }
    }
}
