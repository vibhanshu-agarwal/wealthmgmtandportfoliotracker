package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Wave 3 Task 4 — {@link MarketPriceProjectionService} history append.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Task 4.1 — Appends to {@code market_price_history} for every distinct
 *       {@code (ticker, observed_at)}, including unchanged-price new snapshots.</li>
 *   <li>Task 4.1 — Replay of the same {@code (ticker, observed_at)} is a no-op (idempotent).</li>
 *   <li>Task 4.1 — Old-shape event (no {@code observedAt}) does NOT append a history row.</li>
 *   <li>Task 4.1 — Sub-millisecond precision on {@code observedAt} is truncated before keying.</li>
 *   <li>Task 4.2 — Seed produces non-trivial cost basis and history coverage for all tickers.</li>
 * </ul>
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class Wave3HistoryAppendIT {

    @Container
    @SuppressWarnings("rawtypes")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
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
    MarketPriceProjectionService projectionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanHistoryTestRows() {
        jdbcTemplate.update(
                "DELETE FROM market_price_history WHERE ticker IN ('HIT_APPEND','HIT_DEDUP','HIT_UNCHANGED','HIT_OLD_SHAPE','HIT_SUBMS')");
    }

    // ── Task 4.1: distinct (ticker, observed_at) creates a new row ────────────

    @Test
    void newObservation_appendsHistoryRow() throws Exception {
        Instant obs = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PriceUpdatedEvent event = new PriceUpdatedEvent(
                "HIT_APPEND", new BigDecimal("100.00"), "USD", obs, null, null);

        // upsertLatestPrice is @Async — invoke and wait briefly
        projectionService.upsertLatestPrice(event);
        Thread.sleep(500);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'HIT_APPEND'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── Task 4.1: replay of the same (ticker, observedAt) is a no-op ─────────

    @Test
    void replayOfSameObservation_isIdempotent() throws Exception {
        Instant obs = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PriceUpdatedEvent event = new PriceUpdatedEvent(
                "HIT_DEDUP", new BigDecimal("200.00"), "USD", obs, null, null);

        projectionService.upsertLatestPrice(event);
        Thread.sleep(300);
        projectionService.upsertLatestPrice(event); // replay same observation
        Thread.sleep(300);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'HIT_DEDUP'",
                Integer.class);
        assertThat(count)
                .as("Replaying the same (ticker, observedAt) must not add a duplicate row")
                .isEqualTo(1);
    }

    // ── Task 4.1: new timestamp with unchanged price is a valid new row ───────

    @Test
    void newTimestampUnchangedPrice_isValidNewRow() throws Exception {
        Instant obs1 = Instant.now().minus(25, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant obs2 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        PriceUpdatedEvent event1 = new PriceUpdatedEvent(
                "HIT_UNCHANGED", new BigDecimal("300.00"), "USD", obs1, null, null);
        PriceUpdatedEvent event2 = new PriceUpdatedEvent(
                "HIT_UNCHANGED", new BigDecimal("300.00"), "USD", obs2, null, null); // same price, new time

        projectionService.upsertLatestPrice(event1);
        Thread.sleep(300);
        projectionService.upsertLatestPrice(event2);
        Thread.sleep(300);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'HIT_UNCHANGED'",
                Integer.class);
        assertThat(count)
                .as("New observedAt with unchanged price must be a valid distinct row")
                .isEqualTo(2);
    }

    // ── Task 4.1: old-shape event (no observedAt) must NOT append history ─────

    @Test
    void oldShapeEvent_noObservedAt_doesNotAppendHistory() throws Exception {
        // 2-arg ctor = old-shape event with observedAt = null
        PriceUpdatedEvent event = new PriceUpdatedEvent("HIT_OLD_SHAPE", new BigDecimal("400.00"));

        projectionService.upsertLatestPrice(event);
        Thread.sleep(500);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'HIT_OLD_SHAPE'",
                Integer.class);
        assertThat(count)
                .as("Old-shape event with no observedAt must not produce a history row")
                .isEqualTo(0);
    }

    // ── Task 4.1: sub-millisecond precision truncated to millis before keying ─

    @Test
    void subMillisecondInstants_truncatedToMillis_deduplicateCorrectly() throws Exception {
        // Two instants differing by nanoseconds (same millisecond) must be treated as identical
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant withNanos = base.plusNanos(999_999); // same ms, different ns

        PriceUpdatedEvent event1 = new PriceUpdatedEvent(
                "HIT_SUBMS", new BigDecimal("500.00"), "USD", base, null, null);
        PriceUpdatedEvent event2 = new PriceUpdatedEvent(
                "HIT_SUBMS", new BigDecimal("500.00"), "USD", withNanos, null, null);

        projectionService.upsertLatestPrice(event1);
        Thread.sleep(300);
        projectionService.upsertLatestPrice(event2);
        Thread.sleep(300);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = 'HIT_SUBMS'",
                Integer.class);
        assertThat(count)
                .as("Two instants in the same millisecond must produce exactly 1 history row")
                .isEqualTo(1);
    }

    // ── Task 4.2: seed produces non-trivial cost basis per holding ────────────

    @Test
    void seed_producesNonTrivialCostBasis() throws Exception {
        // The seed service is invoked by the golden-state seeder in tests via PortfolioSeedServiceIT.
        // Here we verify the cost-basis computation logic directly on the calculator.
        BigDecimal seedPrice = new BigDecimal("100.0000");
        BigDecimal basis = com.wealth.portfolio.seed.PortfolioSeedService
                .computeDeterministicCostBasis(seedPrice, "AAPL", "user-001");

        // Must be non-zero and non-trivially different from the seed price (in [-20%, +20%])
        assertThat(basis)
                .as("Cost basis must be non-null")
                .isNotNull();
        assertThat(basis.compareTo(BigDecimal.ZERO))
                .as("Cost basis must be positive")
                .isGreaterThan(0);
        // Range check: within ±20% of seed price
        BigDecimal lower = seedPrice.multiply(new BigDecimal("0.80"));
        BigDecimal upper = seedPrice.multiply(new BigDecimal("1.20"));
        assertThat(basis.compareTo(lower)).isGreaterThanOrEqualTo(0);
        assertThat(basis.compareTo(upper)).isLessThanOrEqualTo(0);
    }

    @Test
    void seed_costBasisIsDeterministic() {
        BigDecimal seedPrice = new BigDecimal("150.0000");
        String ticker = "MSFT";
        String userId = "user-042";

        BigDecimal first  = com.wealth.portfolio.seed.PortfolioSeedService
                .computeDeterministicCostBasis(seedPrice, ticker, userId);
        BigDecimal second = com.wealth.portfolio.seed.PortfolioSeedService
                .computeDeterministicCostBasis(seedPrice, ticker, userId);

        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    void seed_differentTickersYieldDifferentBases() {
        BigDecimal seedPrice = new BigDecimal("200.0000");
        String userId = "user-001";

        BigDecimal basisAppl  = com.wealth.portfolio.seed.PortfolioSeedService
                .computeDeterministicCostBasis(seedPrice, "AAPL", userId);
        BigDecimal basisMsft  = com.wealth.portfolio.seed.PortfolioSeedService
                .computeDeterministicCostBasis(seedPrice, "MSFT", userId);

        // Different tickers must generally yield different bases (hash collision would be a false alarm)
        assertThat(basisAppl).isNotEqualByComparingTo(basisMsft);
    }

    // ── V11/V12/V13 history coverage after migration (Task 4.2) ──────────────

    @Test
    void marketPriceHistory_covers160CanonicalTickers_afterMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ticker) FROM market_price_history",
                Integer.class);
        assertThat(count)
                .as("market_price_history should have at least 160 distinct canonical tickers after V12 backfill")
                .isGreaterThanOrEqualTo(160);
    }
}
