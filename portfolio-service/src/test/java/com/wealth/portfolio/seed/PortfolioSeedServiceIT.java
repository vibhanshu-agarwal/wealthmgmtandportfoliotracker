package com.wealth.portfolio.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import java.math.BigDecimal;
import java.time.Instant;
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
        SeedResult first = seedService.seed(E2E_USER_ID, observedVersion(E2E_USER_ID));
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
        Map<String, Instant> costBasisAsOfFirst = holdingsFirst.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getCostBasisAsOf));

        // ── Second invocation: same aggregate, no transition, no child churn ──
        //
        // This assertion set replaces the old delete-and-replace expectation, which asserted
        // the first portfolio was *gone*. Identity now survives, and an already-golden state
        // is a genuine no-op: same id, same version, same updated_at, byte-identical rows.
        //
        // It is also the scale regression: the desired quantity is a scale-0 BigDecimal from
        // the catalog while the persisted column is NUMERIC(19,8), so a comparison using
        // equals() rather than compareTo() would see a difference here and force a spurious
        // transition. A version bump below therefore also means the tuple comparison broke.
        Portfolio before = portfolioRepository.findById(first.portfolioId()).orElseThrow();
        UUID observedId = before.getId();
        long observedVersion = before.getVersion();
        Instant observedUpdatedAt = before.getUpdatedAt();
        Instant observedCreatedAt = before.getCreatedAt();
        List<Map<String, Object>> holdingRowsBefore = snapshotHoldingRows(observedId);

        SeedResult second = seedService.seed(E2E_USER_ID, observedVersion);

        List<Portfolio> portfoliosAfterSecond = portfolioRepository.findByUserId(E2E_USER_ID);
        assertThat(portfoliosAfterSecond).hasSize(1);
        Portfolio after = portfolioRepository.findById(observedId).orElseThrow();

        assertThat(second.portfolioId())
                .as("the seed must converge the existing aggregate, not create a new one")
                .isEqualTo(observedId);
        assertThat(after.getCreatedAt()).isEqualTo(observedCreatedAt);
        assertThat(after.getVersion())
                .as("an identical golden tuple is a no-op: no version transition")
                .isEqualTo(observedVersion);
        assertThat(after.getUpdatedAt())
                .as("a no-op must not advance updated_at")
                .isEqualTo(observedUpdatedAt);
        assertThat(second.holdingsInserted()).isEqualTo(registry.active().size());
        assertThat(snapshotHoldingRows(observedId))
                .as("a no-op must not delete and reinsert child rows")
                .isEqualTo(holdingRowsBefore);

        List<AssetHolding> holdingsSecond = assetHoldingRepository.findByPortfolio(portfoliosAfterSecond.get(0));
        assertThat(holdingsSecond).hasSize(expectedHoldings);

        // ── Determinism: quantities and cost_basis_as_of are byte-identical per ticker ──
        Map<String, BigDecimal> quantitiesSecond = holdingsSecond.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        assertThat(quantitiesSecond).containsExactlyInAnyOrderEntriesOf(quantitiesFirst);

        Map<String, Instant> costBasisAsOfSecond = holdingsSecond.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getCostBasisAsOf));
        assertThat(costBasisAsOfSecond)
                .as("fixed app.demo.cost-basis-anchor must make cost_basis_as_of identical across seeds")
                .containsExactlyInAnyOrderEntriesOf(costBasisAsOfFirst);
        assertThat(costBasisAsOfSecond.values())
                .allMatch(asOf -> asOf.equals(costBasisAsOfFirst.values().iterator().next()));

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
        SeedResult result = seedService.seed(cbUserId, observedVersion(cbUserId));

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
        seedService.seed(E2E_USER_ID, observedVersion(E2E_USER_ID));
        var summary = portfolioService.getSummary(E2E_USER_ID);
        assertThat(summary.totalValue())
                .as("GET /api/portfolio/summary totalValue must be > 0 after seed")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(summary.totalHoldings()).isEqualTo(registry.active().size());
    }

    // ────────────────────────── Task 6.4: state/request matrix ──────────────────────────

    @Test
    void absentAggregate_withExpectedZero_createsOneAggregateAtVersionOne() {
        String user = freshUser();

        SeedResult result = seedService.seed(user, 0L);

        Portfolio created = portfolioRepository.findById(result.portfolioId()).orElseThrow();
        assertThat(portfolioRepository.findByUserId(user)).hasSize(1);
        assertThat(created.getVersion())
                .as("Aggregate_Creation is never a no-op: externally visible at version 1")
                .isEqualTo(1L);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        assertThat(snapshotHoldingRows(created.getId())).hasSize(registry.active().size());
    }

    @Test
    void absentAggregate_withNonZeroExpected_conflictsAndCreatesNothing() {
        String user = freshUser();

        assertThatThrownBy(() -> seedService.seed(user, 3L))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(e -> assertThat(
                                ((PortfolioVersionConflictException) e).currentVersion().getAsLong())
                        .as("current version of an absent aggregate is the virtual zero")
                        .isEqualTo(0L));

        assertThat(portfolioRepository.findByUserId(user))
                .as("a rejected creation must leave no aggregate behind")
                .isEmpty();
    }

    @Test
    void existingNonGolden_withMatchingVersion_convergesInPlaceWithExactlyOneTransition() {
        String user = freshUser();
        SeedResult created = seedService.seed(user, 0L);
        UUID portfolioId = created.portfolioId();
        Portfolio before = portfolioRepository.findById(portfolioId).orElseThrow();
        Instant createdAt = before.getCreatedAt();
        Instant updatedAtBefore = before.getUpdatedAt();
        long versionBefore = before.getVersion();

        // Drive the stored tuple away from golden without touching the parent.
        jdbc.update("UPDATE asset_holdings SET quantity = quantity + 1 WHERE portfolio_id = ?::uuid",
                portfolioId.toString());

        SeedResult reconverged = seedService.seed(user, versionBefore);

        Portfolio after = portfolioRepository.findById(portfolioId).orElseThrow();
        assertThat(reconverged.portfolioId()).isEqualTo(portfolioId);
        assertThat(after.getCreatedAt()).as("identity survives convergence").isEqualTo(createdAt);
        assertThat(after.getVersion())
                .as("exactly one transition for one converging write")
                .isEqualTo(versionBefore + 1);
        assertThat(after.getUpdatedAt()).isAfter(updatedAtBefore);
        assertGoldenTupleFor(user, portfolioId);
    }

    @Test
    void existingGolden_withStaleVersion_conflictsBeforeEqualityCanProduceSuccess() {
        String user = freshUser();
        SeedResult created = seedService.seed(user, 0L);
        UUID portfolioId = created.portfolioId();
        Portfolio before = portfolioRepository.findById(portfolioId).orElseThrow();
        long currentVersion = before.getVersion();
        Instant updatedAtBefore = before.getUpdatedAt();
        List<Map<String, Object>> holdingRowsBefore = snapshotHoldingRows(portfolioId);

        insertSentinelPriceRows();
        List<Map<String, Object>> pricesBefore = snapshotMarketPrices();
        List<Map<String, Object>> historyBefore = snapshotMarketPriceHistory();

        // The stored tuple already equals golden, so only version arbitration can reject this.
        assertThatThrownBy(() -> seedService.seed(user, currentVersion - 1))
                .isInstanceOf(PortfolioVersionConflictException.class);

        Portfolio after = portfolioRepository.findById(portfolioId).orElseThrow();
        assertThat(after.getVersion())
                .as("a stale seed must not transition an already-golden aggregate")
                .isEqualTo(currentVersion);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(snapshotHoldingRows(portfolioId)).isEqualTo(holdingRowsBefore);
        assertPriceTablesUnchanged(pricesBefore, historyBefore, "after rejected stale seed");
    }

    @Test
    void differingAverageCostBasis_forcesExactlyOneTransition() {
        assertSingleTransitionAfter("avg_cost_basis = avg_cost_basis + 1");
    }

    @Test
    void differingCostBasisCurrency_forcesExactlyOneTransition() {
        assertSingleTransitionAfter("cost_basis_currency = 'ZWL'");
    }

    @Test
    void differingCostBasisSource_forcesExactlyOneTransition() {
        assertSingleTransitionAfter("cost_basis_source = 'MANUAL'");
    }

    @Test
    void differingCostBasisAnchor_forcesExactlyOneTransition() {
        assertSingleTransitionAfter("cost_basis_as_of = timestamp '1999-12-31 23:59:59'");
    }

    @Test
    void seedOfOneAccountLeavesAnotherAccountByteIdentical() {
        String other = freshUser();
        seedService.seed(other, 0L);
        UUID otherId = portfolioRepository.findByUserId(other).get(0).getId();
        Map<String, Object> otherPortfolioBefore = portfolioRow(otherId);
        List<Map<String, Object>> otherHoldingsBefore = snapshotHoldingRows(otherId);

        String target = freshUser();
        seedService.seed(target, 0L);

        assertThat(portfolioRow(otherId))
                .as("only the selected target may change")
                .isEqualTo(otherPortfolioBefore);
        assertThat(snapshotHoldingRows(otherId)).isEqualTo(otherHoldingsBefore);
    }

    // ────────────────────────────────── helpers ──────────────────────────────────

    /**
     * Mutates one column away from golden, then proves the corrective seed produces exactly one
     * version transition and restores the full tuple. Proves the comparison is full-tuple: a
     * comparison over ticker and quantity alone would treat these states as already converged.
     */
    private void assertSingleTransitionAfter(String setClause) {
        String user = freshUser();
        SeedResult created = seedService.seed(user, 0L);
        UUID portfolioId = created.portfolioId();
        long versionBefore = portfolioRepository.findById(portfolioId).orElseThrow().getVersion();

        int mutated = jdbc.update(
                "UPDATE asset_holdings SET " + setClause + " WHERE portfolio_id = ?::uuid",
                portfolioId.toString());
        assertThat(mutated).as("the fixture must actually diverge from golden").isPositive();

        seedService.seed(user, versionBefore);

        Portfolio after = portfolioRepository.findById(portfolioId).orElseThrow();
        assertThat(after.getId()).isEqualTo(portfolioId);
        assertThat(after.getVersion())
                .as("one differing column is one transition, not zero and not two")
                .isEqualTo(versionBefore + 1);
        assertGoldenTupleFor(user, portfolioId);

        // A second seed at the restored version must now be a true no-op.
        long restored = after.getVersion();
        List<Map<String, Object>> rows = snapshotHoldingRows(portfolioId);
        seedService.seed(user, restored);
        assertThat(portfolioRepository.findById(portfolioId).orElseThrow().getVersion())
                .as("the restored tuple must compare equal on the next seed")
                .isEqualTo(restored);
        assertThat(snapshotHoldingRows(portfolioId)).isEqualTo(rows);
    }

    /** Asserts the persisted holdings equal the deterministic desired tuple for {@code userId}. */
    private void assertGoldenTupleFor(String userId, UUID portfolioId) {
        Map<String, PortfolioSeedService.DesiredHolding> desired =
                seedService.desiredHoldings(userId).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                PortfolioSeedService.DesiredHolding::ticker, d -> d));
        List<AssetHolding> actual = assetHoldingRepository.findByPortfolio(
                portfolioRepository.findById(portfolioId).orElseThrow());

        assertThat(actual).hasSize(desired.size());
        for (AssetHolding holding : actual) {
            PortfolioSeedService.DesiredHolding want = desired.get(holding.getAssetTicker());
            assertThat(want).as("unexpected ticker %s", holding.getAssetTicker()).isNotNull();
            assertThat(holding.getQuantity()).isEqualByComparingTo(want.quantity());
            assertThat(holding.getAvgCostBasis()).isEqualByComparingTo(want.avgCostBasis());
            assertThat(holding.getCostBasisCurrency()).isEqualTo(want.costBasisCurrency());
            assertThat(holding.getCostBasisSource()).isEqualTo(want.costBasisSource());
            assertThat(holding.getCostBasisAsOf()).isEqualTo(want.costBasisAsOf());
        }
    }

    /** The caller's own observation, exactly as a production caller must supply it. */
    private long observedVersion(String userId) {
        return portfolioRepository.findByUserId(userId).stream()
                .findFirst()
                .map(Portfolio::getVersion)
                .orElse(0L);
    }

    /** Every column of every child row, ordered deterministically. */
    private List<Map<String, Object>> snapshotHoldingRows(UUID portfolioId) {
        return jdbc.queryForList(
                "SELECT * FROM asset_holdings WHERE portfolio_id = ?::uuid ORDER BY asset_ticker",
                portfolioId.toString());
    }

    private Map<String, Object> portfolioRow(UUID portfolioId) {
        return jdbc.queryForMap(
                "SELECT * FROM portfolios WHERE id = ?::uuid", portfolioId.toString());
    }

    /** An independently established fixture per case, so no test inherits another state. */
    private static String freshUser() {
        return UUID.randomUUID().toString();
    }
}
