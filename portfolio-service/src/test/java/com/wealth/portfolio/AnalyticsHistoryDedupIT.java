package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rehearsal defect #6 (F3): the performance series uses one history row per ticker per UTC day —
 * the latest observation that day — instead of summing every row, which counted a day with N
 * observations N times. Runs the real analytics SQL against Postgres.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class AnalyticsHistoryDedupIT {

    private static final String TICKER = "MSFT";

    @Container
    @SuppressWarnings("rawtypes")
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
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired PortfolioAnalyticsService analyticsService;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void sameDayRows_useTheLatestRow_andMidnightStartsTheNextUtcDay() {
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TICKER);
        String userId = seedUserHolding(10);
        jdbcTemplate.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at) "
                        + "VALUES (?, 100.00, 'USD', now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')",
                TICKER);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate spike = today.minusDays(5);
        LocalDate next = spike.plusDays(1);
        // Ordinary days: one noon row each at 100, enough dates to avoid the synthetic series.
        for (int back = 12; back >= 1; back--) {
            LocalDate d = today.minusDays(back);
            if (!d.equals(spike) && !d.equals(next)) {
                history(d + " 12:00:00.000", "100.00");
            }
        }
        // The spike day: three rows; only the last one (23:59:59.999, price 200) may count.
        history(spike + " 00:05:00.000", "150.00");
        history(spike + " 08:00:00.000", "100.00");
        history(spike + " 23:59:59.999", "200.00");
        // One millisecond later is the next UTC day, not the spike day.
        history(next + " 00:00:00.000", "300.00");
        evict(userId);

        Map<String, BigDecimal> byDate = analyticsService.getAnalytics(userId).performanceSeries().stream()
                .collect(Collectors.toMap(PortfolioAnalyticsDto.PerformancePointDto::date,
                        PortfolioAnalyticsDto.PerformancePointDto::value));

        assertThat(byDate.get(spike.toString())).isEqualByComparingTo("2000");   // 10 × 200, not 10 × 450
        assertThat(byDate.get(next.toString())).isEqualByComparingTo("3000");    // 10 × 300
        assertThat(byDate.get(today.minusDays(3).toString())).isEqualByComparingTo("1000");
        assertThat(byDate.values()).allSatisfy(v -> assertThat(v).isLessThanOrEqualTo(new BigDecimal("3000")));
    }

    @Test
    void theUniqueKeyTheTieBreakerReliesOn_rejectsAnIdenticalObservation() {
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TICKER);
        history("2026-08-10 08:00:00.000", "100.00");

        assertThatThrownBy(() -> history("2026-08-10 08:00:00.000", "101.00"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void history(String utcText, String price) {
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) "
                        + "VALUES (?, 'USD', ?::numeric, ?::timestamp)",
                TICKER, price, utcText);
    }

    private String seedUserHolding(int quantity) {
        String userId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?::uuid, ?)", userId, userId + "@dedup.test");
        String portfolioId = jdbcTemplate.queryForObject(
                "INSERT INTO portfolios (user_id) VALUES (?) RETURNING id::text", String.class, userId);
        jdbcTemplate.update(
                "INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity) VALUES (?::uuid, ?, ?)",
                portfolioId, TICKER, quantity);
        return userId;
    }

    private void evict(String userId) {
        var cache = cacheManager.getCache("portfolio-analytics");
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
