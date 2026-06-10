package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 6 Task 10.1 — cross-service integration test for dashboard data accuracy.
 *
 * <p>Exercises the full backend pipeline:
 * <ol>
 *   <li>Enriched {@link PriceUpdatedEvent} is projected via {@link MarketPriceProjectionService}
 *       (current price upsert + {@code market_price_history} append).</li>
 *   <li>{@link PortfolioAnalyticsService} computes honest 24h change, real cost-basis P&amp;L,
 *       and FX-converted valuations from the updated read model.</li>
 *   <li>Aggregate reconciliation invariants hold: {@code totalValue == Σ currentValueBase}
 *       and P&amp;L identity when basis is present.</li>
 * </ol>
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class Wave6DashboardDataAccuracyIT {

    private static final String DEV_USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TEST_TICKER = "W6_E2E";

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
    PortfolioAnalyticsService analyticsService;

    @Autowired
    org.springframework.cache.CacheManager cacheManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String portfolioId;

    @BeforeEach
    void seedIsolatedHolding() {
        jdbcTemplate.update("DELETE FROM asset_holdings WHERE asset_ticker = ?", TEST_TICKER);
        jdbcTemplate.update(
                "DELETE FROM market_price_history WHERE ticker = ?", TEST_TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TEST_TICKER);

        // Dev user is seeded by V4__seed_local_dev_user.sql
        jdbcTemplate.update("""
                INSERT INTO portfolios (user_id)
                SELECT ?
                WHERE NOT EXISTS (SELECT 1 FROM portfolios WHERE user_id = ?)
                """, DEV_USER_ID, DEV_USER_ID);

        portfolioId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM portfolios WHERE user_id = ? LIMIT 1",
                String.class, DEV_USER_ID);

        // Reference observation ~24h ago (within the 18–36h tolerance window)
        Instant referenceAt = Instant.now().minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update(
                """
                INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                VALUES (?, 'USD', ?, ?)
                """,
                TEST_TICKER, new BigDecimal("100.0000"), java.sql.Timestamp.from(referenceAt));

        // Holding with real cost basis (Task 5.1)
        jdbcTemplate.update(
                """
                INSERT INTO asset_holdings
                    (portfolio_id, asset_ticker, quantity,
                     avg_cost_basis, cost_basis_currency, cost_basis_source, cost_basis_as_of)
                VALUES (?::uuid, ?, 10.0, 95.0000, 'USD', 'TEST', now())
                ON CONFLICT (portfolio_id, asset_ticker) DO UPDATE
                    SET quantity = EXCLUDED.quantity,
                        avg_cost_basis = EXCLUDED.avg_cost_basis,
                        cost_basis_currency = EXCLUDED.cost_basis_currency,
                        cost_basis_source = EXCLUDED.cost_basis_source,
                        cost_basis_as_of = EXCLUDED.cost_basis_as_of
                """,
                portfolioId, TEST_TICKER);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM asset_holdings WHERE portfolio_id = ?::uuid AND asset_ticker = ?",
                portfolioId, TEST_TICKER);
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TEST_TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TEST_TICKER);
        evictAnalyticsCache();
    }

    private void evictAnalyticsCache() {
        var cache = cacheManager.getCache("portfolio-analytics");
        if (cache != null) {
            cache.evict(DEV_USER_ID);
        }
    }

    // ── Task 10.1: enriched event → projection → analytics reconciliation ─────

    @Test
    void enrichedEvent_throughProjection_producesHonestAnalyticsAndReconciliation() throws Exception {
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant previousReferenceAt = Instant.now().minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        BigDecimal newPrice = new BigDecimal("110.0000");
        BigDecimal previousReferencePrice = new BigDecimal("100.0000");

        PriceUpdatedEvent event = new PriceUpdatedEvent(
                TEST_TICKER,
                newPrice,
                "USD",
                observedAt,
                previousReferencePrice,
                previousReferenceAt);

        projectionService.upsertLatestPrice(event);
        Thread.sleep(500);
        evictAnalyticsCache();

        // History append: reference row (seeded) + new observation from event
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?",
                Integer.class, TEST_TICKER);
        assertThat(historyCount)
                .as("Projection must append a history row for the enriched event")
                .isEqualTo(2);

        BigDecimal projectedPrice = jdbcTemplate.queryForObject(
                "SELECT current_price FROM market_prices WHERE ticker = ?",
                BigDecimal.class, TEST_TICKER);
        assertThat(projectedPrice).isEqualByComparingTo(newPrice);

        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

        HoldingAnalyticsDto holding = dto.holdings().stream()
                .filter(h -> TEST_TICKER.equals(h.ticker()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected holding for " + TEST_TICKER));

        // Price and FX-converted value
        assertThat(holding.currentPrice()).isEqualByComparingTo(newPrice);
        assertThat(holding.currentValueBase()).isEqualByComparingTo("1100.0000");

        // Task 5.1: real P&L from cost basis (10 × 95 = 950 cost; value 1100 → P&L 150)
        assertThat(holding.avgCostBasis()).isEqualByComparingTo("95.0000");
        assertThat(holding.unrealizedPnL()).isEqualByComparingTo("150.0000");
        assertThat(holding.unrealizedPnLPercent()).isNotNull();

        // Task 5.2: tolerance-window change with reference metadata
        assertThat(holding.change24hAbsolute()).isEqualByComparingTo("10.0000");
        assertThat(holding.change24hPercent()).isEqualByComparingTo("10.0000");
        assertThat(holding.change24hReferenceAt()).isNotNull();
        assertThat(holding.changeBasis()).isEqualTo("WITHIN_24H_WINDOW");

        // Task 5.4: canonical display asset class (unknown ticker → OTHER)
        assertThat(holding.displayAssetClass()).isEqualTo("OTHER");

        // Property 1 / Requirement 1.4: totalValue == Σ currentValueBase
        BigDecimal sumOfHoldings = dto.holdings().stream()
                .map(HoldingAnalyticsDto::currentValueBase)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dto.totalValue()).isEqualByComparingTo(sumOfHoldings);

        // Property 2: P&L identity when basis is present
        if (dto.totalUnrealizedPnL() != null) {
            BigDecimal expectedPnL = dto.totalValue().subtract(dto.totalCostBasis());
            assertThat(dto.totalUnrealizedPnL()).isEqualByComparingTo(expectedPnL);
        }
    }

    @Test
    void enrichedEvent_replay_isIdempotent_andAnalyticsStayConsistent() throws Exception {
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PriceUpdatedEvent event = new PriceUpdatedEvent(
                TEST_TICKER, new BigDecimal("110.0000"), "USD", observedAt, null, null);

        projectionService.upsertLatestPrice(event);
        Thread.sleep(300);
        projectionService.upsertLatestPrice(event);
        Thread.sleep(300);
        evictAnalyticsCache();

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?",
                Integer.class, TEST_TICKER);
        assertThat(historyCount)
                .as("Replay of same (ticker, observedAt) must not duplicate history")
                .isEqualTo(2); // reference seed + one event observation

        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);
        HoldingAnalyticsDto holding = dto.holdings().stream()
                .filter(h -> TEST_TICKER.equals(h.ticker()))
                .findFirst()
                .orElseThrow();

        assertThat(holding.currentPrice()).isEqualByComparingTo("110.0000");
        assertThat(holding.change24hAbsolute()).isNotNull();
    }
}
