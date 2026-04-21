package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code GET /api/portfolio/analytics}.
 *
 * <p>Spins up a real PostgreSQL container via Testcontainers. Flyway migrations
 * (V1–V6) run automatically, seeding 50 days of price history for AAPL, TSLA, BTC
 * and a demo portfolio for the dev user ({@code 00000000-0000-0000-0000-000000000001}).
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioAnalyticsIntegrationTest {

    private static final String ANALYTICS_PATH = "/api/portfolio/analytics";
    private static final String DEV_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Container
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
        // Disable Kafka — not needed for analytics tests
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration");
    }

    @Autowired
    PortfolioAnalyticsController analyticsController;

    @Autowired
    PortfolioAnalyticsService analyticsService;

    @Autowired
    org.springframework.cache.CacheManager cacheManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(analyticsController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Test 1: 200 OK with valid X-User-Id ──────────────────────────────────

    @Test
    void validUser_returns200WithDto() throws Exception {
        mockMvc.perform(get(ANALYTICS_PATH)
                        .header("X-User-Id", DEV_USER_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.bestPerformer").exists())
                .andExpect(jsonPath("$.worstPerformer").exists())
                .andExpect(jsonPath("$.performanceSeries").isArray());
    }

    // ── Test 2: 400 when X-User-Id header is missing ─────────────────────────

    @Test
    void missingHeader_returns400() throws Exception {
        mockMvc.perform(get(ANALYTICS_PATH)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── Test 3: 404 when userId does not exist ────────────────────────────────

    @Test
    void unknownUser_returns404() throws Exception {
        mockMvc.perform(get(ANALYTICS_PATH)
                        .header("X-User-Id", "ffffffff-ffff-ffff-ffff-ffffffffffff")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ── Test 4: Performer ordering invariant (Property 1) ────────────────────
    // bestPerformer.change24hPercent >= worstPerformer.change24hPercent

    @Test
    void performerOrderingInvariant() {
        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

        assertThat(dto.bestPerformer().change24hPercent())
                .isGreaterThanOrEqualTo(dto.worstPerformer().change24hPercent());
    }

    // ── Test 5: Performance series is non-null and ascending by date ──────────

    @Test
    void performanceSeriesAscendingByDate() {
        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

        var series = dto.performanceSeries();
        assertThat(series).isNotNull();

        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i).date())
                    .as("series[%d].date > series[%d].date", i, i - 1)
                    .isGreaterThan(series.get(i - 1).date());
        }
    }

    // ── Test 6: FX conversion — EUR holding has currentValueBase ≠ raw value ──

    @Test
    void eurHolding_fxConversionApplied() {
        // Seed a EUR-priced asset into market_prices
        jdbcTemplate.update("""
                INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at)
                VALUES ('EUR_TEST', 100.0000, 'EUR', now())
                ON CONFLICT (ticker) DO UPDATE
                    SET current_price = EXCLUDED.current_price,
                        quote_currency = EXCLUDED.quote_currency,
                        updated_at = EXCLUDED.updated_at
                """);

        // Ensure the dev user has a portfolio; create one if absent
        jdbcTemplate.update("""
                INSERT INTO portfolios (user_id)
                SELECT ?
                WHERE NOT EXISTS (SELECT 1 FROM portfolios WHERE user_id = ?)
                """, DEV_USER_ID, DEV_USER_ID);

        String portfolioId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM portfolios WHERE user_id = ? LIMIT 1",
                String.class, DEV_USER_ID);

        jdbcTemplate.update("""
                INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity)
                VALUES (?::uuid, 'EUR_TEST', 10.0)
                ON CONFLICT (portfolio_id, asset_ticker) DO UPDATE SET quantity = EXCLUDED.quantity
                """, portfolioId);

        try {
            // Evict cache so the EUR_TEST holding is included in the fresh result
            var cache = cacheManager.getCache("portfolio-analytics");
            if (cache != null) cache.evict(DEV_USER_ID);

            PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

            var eurHolding = dto.holdings().stream()
                    .filter(h -> "EUR_TEST".equals(h.ticker()))
                    .findFirst();

            assertThat(eurHolding).isPresent();

            // currentValueBase = 10 × 100 × fxRate(EUR→USD)
            // Local config has EUR rate ~1.087, so value ≠ raw 1000
            BigDecimal rawValue = new BigDecimal("1000.0000");
            assertThat(eurHolding.get().currentValueBase())
                    .isNotEqualByComparingTo(rawValue);
        } finally {
            // Clean up seeded data
            jdbcTemplate.update(
                    "DELETE FROM asset_holdings WHERE portfolio_id = ?::uuid AND asset_ticker = 'EUR_TEST'",
                    portfolioId);
            jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = 'EUR_TEST'");
        }
    }

    // ── Test 7: P&L identity (Property 2) ────────────────────────────────────
    // totalUnrealizedPnL == totalValue - totalCostBasis

    @Test
    void pnlIdentity() {
        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

        BigDecimal expected = dto.totalValue().subtract(dto.totalCostBasis());
        assertThat(dto.totalUnrealizedPnL()).isEqualByComparingTo(expected);
    }

    // ── Test 8: Value decomposition (Property 3) ─────────────────────────────
    // totalValue == sum of holding.currentValueBase

    @Test
    void totalValueEqualsSumOfHoldingValues() {
        PortfolioAnalyticsDto dto = analyticsService.getAnalytics(DEV_USER_ID);

        BigDecimal sumOfHoldings = dto.holdings().stream()
                .map(PortfolioAnalyticsDto.HoldingAnalyticsDto::currentValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(dto.totalValue()).isEqualByComparingTo(sumOfHoldings);
    }
}
