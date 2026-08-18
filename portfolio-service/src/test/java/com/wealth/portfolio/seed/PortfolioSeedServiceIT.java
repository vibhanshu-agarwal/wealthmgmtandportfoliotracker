package com.wealth.portfolio.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers-backed integration test for {@link PortfolioSeedService}.
 *
 * <p>Validates Requirement 12 of the Golden-State Seeder spec: the seeder produces
 * exactly 1 portfolio + one holding per catalogue ticker, returns the generated
 * {@code portfolioId}, and is idempotent at the value level on the second invocation
 * (counts are stable, per-ticker quantities are byte-identical, and the previous
 * portfolio + its cascaded holdings are cleanly removed).
 *
 * <p>It also guards the market-data boundary: {@code market_prices} and
 * {@code market_price_history} are global tables owned by {@code market-data-service}, and
 * the seeder must leave both byte-identical. The seeder previously overwrote every
 * catalogue ticker's price with a synthetic value on each run, via an endpoint that is
 * reachable in production and invoked there on a schedule.
 *
 * <p>Uses {@link com.wealth.portfolio.TestContainerImages#POSTGRES} to match the production Neon Postgres 18.4 target
 * (design doc §11). Runs as part of the {@code integrationTest} task:
 * {@code ./gradlew :portfolio-service:integrationTest --tests "*PortfolioSeedServiceIT*"}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioSeedServiceIT {

    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Kafka isn't on the seeder's call path. Prevent listener containers from
        // auto-starting to avoid any broker connection attempt, while keeping
        // KafkaProperties in the context so PortfolioKafkaConfig can build its beans.
        // Excluding KafkaAutoConfiguration removes KafkaProperties and breaks the
        // config class, so auto-startup=false is the correct knob.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioService portfolioService;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    @Autowired SeedTickerRegistry registry;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seederEstablishesGoldenStateAndIsIdempotent() {
        // Market data is global and owned by market-data-service. The seeder must not touch
        // it, so snapshot every column of every row in both tables before seeding and assert
        // they are identical afterwards.
        //
        // The sentinel is a ticker absent from the catalogue, carrying a price the seeder's
        // formula would never produce. It fails if the seeder ever wipes-and-rewrites the
        // table wholesale — a mutation that per-ticker comparison over catalogue symbols
        // alone would not notice.
        insertSentinelPriceRows();
        List<Map<String, Object>> pricesBefore = snapshotMarketPrices();
        List<Map<String, Object>> historyBefore = snapshotMarketPriceHistory();

        // ── First invocation: must create 1 portfolio + one holding per active ticker ──
        SeedResult first = seedService.seed(E2E_USER_ID);
        int expectedHoldings = registry.active().size();

        assertThat(first.portfolioId()).as("portfolioId must be returned to the caller").isNotNull();
        assertThat(first.holdingsInserted()).isEqualTo(expectedHoldings);

        List<Portfolio> portfoliosAfterFirst = portfolioRepository.findByUserId(E2E_USER_ID);
        assertThat(portfoliosAfterFirst).hasSize(1);
        assertThat(portfoliosAfterFirst.get(0).getId()).isEqualTo(first.portfolioId());

        Portfolio portfolioFirst = portfoliosAfterFirst.get(0);
        List<AssetHolding> holdingsFirst = assetHoldingRepository.findByPortfolio(portfolioFirst);
        assertThat(holdingsFirst).hasSize(expectedHoldings);

        Set<String> registryTickers = registry.active().stream()
                .map(SeedTicker::ticker).collect(Collectors.toSet());
        Set<String> holdingTickers = holdingsFirst.stream()
                .map(AssetHolding::getAssetTicker).collect(Collectors.toSet());
        assertThat(holdingTickers).isEqualTo(registryTickers);
        assertThat(holdingTickers).doesNotContain("TATAMOTORS.NS");

        assertPriceTablesUnchanged(pricesBefore, historyBefore, "after first seed");

        Map<String, BigDecimal> quantitiesFirst = holdingsFirst.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));

        // ── Second invocation: counts unchanged, prior portfolio row gone, new one present ──
        SeedResult second = seedService.seed(E2E_USER_ID);

        List<Portfolio> portfoliosAfterSecond = portfolioRepository.findByUserId(E2E_USER_ID);
        assertThat(portfoliosAfterSecond).hasSize(1);
        UUID secondPortfolioId = portfoliosAfterSecond.get(0).getId();
        assertThat(secondPortfolioId).isEqualTo(second.portfolioId());
        // Delete-and-replace semantics: the first portfolio must be gone.
        assertThat(portfolioRepository.findById(first.portfolioId())).isEmpty();

        List<AssetHolding> holdingsSecond = assetHoldingRepository.findByPortfolio(portfoliosAfterSecond.get(0));
        assertThat(holdingsSecond).hasSize(expectedHoldings);

        // ── Determinism: quantities are byte-identical per ticker across runs ──
        Map<String, BigDecimal> quantitiesSecond = holdingsSecond.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        assertThat(quantitiesSecond).containsExactlyInAnyOrderEntriesOf(quantitiesFirst);

        assertPriceTablesUnchanged(pricesBefore, historyBefore, "after second seed");
    }

    /**
     * Asserts the seeder left both global market-data tables byte-identical.
     *
     * <p>This is the regression guard for the defect that motivated the change: the seeder
     * used to upsert one {@code market_prices} row per catalogue ticker with an unconditional
     * {@code DO UPDATE}, overwriting live refreshed prices for every user. The endpoint that
     * invokes it is reachable in production and is called there on a schedule.
     *
     * <p>Compares every column of every row, in a deterministic order, rather than a
     * per-ticker price map and a row count. Value equality on the raw column maps is the
     * strict check wanted here: {@link java.math.BigDecimal#equals} is scale-sensitive, so a
     * rewrite preserving the numeric value but changing its scale would still fail, as would
     * any change to {@code updated_at}.
     */
    private void assertPriceTablesUnchanged(List<Map<String, Object>> pricesBefore,
                                            List<Map<String, Object>> historyBefore,
                                            String phase) {
        assertThat(snapshotMarketPrices())
                .as("seeder must leave market_prices byte-identical (%s)", phase)
                .isEqualTo(pricesBefore);
        assertThat(snapshotMarketPriceHistory())
                .as("seeder must leave market_price_history byte-identical (%s)", phase)
                .isEqualTo(historyBefore);
    }

    /** Every column of every row, ordered by the primary key. */
    private List<Map<String, Object>> snapshotMarketPrices() {
        return jdbc.queryForList("SELECT * FROM market_prices ORDER BY ticker");
    }

    /** Every column of every row, ordered by the surrogate key. */
    private List<Map<String, Object>> snapshotMarketPriceHistory() {
        return jdbc.queryForList("SELECT * FROM market_price_history ORDER BY id");
    }

    /**
     * Seeds rows the catalogue does not contain, so a wholesale wipe-and-rewrite of either
     * table is detected even though no catalogue ticker would appear to change.
     */
    private void insertSentinelPriceRows() {
        jdbc.update("""
                INSERT INTO market_prices (ticker, current_price, updated_at)
                VALUES ('__SENTINEL__', 4242.4242, timestamp '2020-01-01 00:00:00')
                ON CONFLICT (ticker) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                VALUES ('__SENTINEL__', 'USD', 4242.4242, timestamp '2020-01-01 00:00:00')
                """);
    }

    // ── Wave 3 / Task 4.2: seeder writes non-trivial avg_cost_basis per holding ──

    @Test
    void seeder_writesNonTrivialCostBasis() {
        String cbUserId = E2E_USER_ID + "-cb";
        SeedResult result = seedService.seed(cbUserId);

        // Verify via raw JDBC (bypasses JPA cache) — every active holding must have cost basis
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT h.asset_ticker, h.avg_cost_basis, h.cost_basis_currency,
                       h.cost_basis_source, h.cost_basis_as_of
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                WHERE p.id = ?::uuid
                """,
                result.portfolioId().toString());

        assertThat(rows).hasSize(registry.active().size());

        for (Map<String, Object> row : rows) {
            String ticker = (String) row.get("asset_ticker");
            // Every holding must have a cost basis set (non-null) by the seeder.
            assertThat(row.get("avg_cost_basis"))
                    .as("avg_cost_basis must not be null for %s", ticker)
                    .isNotNull();
            // Non-negative: micro-cap assets (e.g. SHIB-USD @ $0.000024) legitimately round to
            // 0.0000 at the NUMERIC(19,4) column scale, so we assert >= 0, not > 0.
            assertThat(((java.math.BigDecimal) row.get("avg_cost_basis")).compareTo(java.math.BigDecimal.ZERO))
                    .as("avg_cost_basis must be non-negative for %s", ticker)
                    .isGreaterThanOrEqualTo(0);
            assertThat(row.get("cost_basis_currency"))
                    .as("cost_basis_currency must not be null for %s", ticker)
                    .isNotNull();
            assertThat(row.get("cost_basis_source"))
                    .as("cost_basis_source must be SEED for %s", ticker)
                    .isEqualTo("SEED");
            assertThat(row.get("cost_basis_as_of"))
                    .as("cost_basis_as_of must not be null for %s", ticker)
                    .isNotNull();
        }

        // For a ticker with a price representable at NUMERIC(19,4) (AAPL ~ $195), the cost
        // basis must be strictly positive and within ±20% of the seed price — confirming the
        // jitter is applied and bounded. (We pick AAPL explicitly rather than rows.get(0),
        // whose order is DB-dependent and may be a sub-scale asset like SHIB-USD.)
        Map<String, Object> aaplRow = rows.stream()
                .filter(r -> "AAPL".equals(r.get("asset_ticker")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AAPL holding must be present in the seed"));
        java.math.BigDecimal aaplBasis = (java.math.BigDecimal) aaplRow.get("avg_cost_basis");
        registry.find("AAPL").ifPresent(t -> {
            java.math.BigDecimal seedPrice = DeterministicPriceCalculator
                    .compute(t.basePrice(), t.ticker(), cbUserId);
            java.math.BigDecimal lower = seedPrice.multiply(new java.math.BigDecimal("0.80"));
            java.math.BigDecimal upper = seedPrice.multiply(new java.math.BigDecimal("1.20"));
            assertThat(aaplBasis.compareTo(java.math.BigDecimal.ZERO))
                    .as("AAPL avg_cost_basis must be strictly positive")
                    .isGreaterThan(0);
            assertThat(aaplBasis.compareTo(lower))
                    .as("AAPL avg_cost_basis must be ≥ 80%% of seed price")
                    .isGreaterThanOrEqualTo(0);
            assertThat(aaplBasis.compareTo(upper))
                    .as("AAPL avg_cost_basis must be ≤ 120%% of seed price")
                    .isLessThanOrEqualTo(0);
        });

        // market_price_history coverage is deliberately NOT asserted here. The seeder no
        // longer writes history rows — market data is owned by market-data-service, and
        // this endpoint is production-reachable. Cost basis above is derived in-memory from
        // the catalogue's basePrice, so it remains deterministic without any price write.

        // Clean up
        portfolioRepository.deleteAll(portfolioRepository.findByUserId(cbUserId));
    }


    /** Mirrors deploy-azure verify assertion (c) against the golden-state seed. */
    @Test
    void seededE2eUser_summaryHasPositiveTotalValue() {
        seedService.seed(E2E_USER_ID);
        var summary = portfolioService.getSummary(E2E_USER_ID);
        assertThat(summary.totalValue())
                .as("GET /api/portfolio/summary totalValue must be > 0 after seed")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(summary.totalHoldings()).isEqualTo(registry.active().size());
    }
}
