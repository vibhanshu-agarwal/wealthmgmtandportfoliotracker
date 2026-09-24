package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rehearsal defect #6 (F3): market-price observation columns hold UTC wall-clock time on every
 * read and write, whatever the JVM default zone and the session zone the driver derives from it.
 *
 * <p>Each concrete subclass runs these scenarios under a different JVM default zone, with its own
 * container and Spring context, and first proves that zone actually took effect on the JDBC
 * session (a fault injection must show it held). Timestamps are seeded and checked as SQL text,
 * never through {@link java.sql.Timestamp}, so the checks themselves are zone-free.
 */
abstract class ObservationUtcInvariantScenarios {

    /** 23:30Z: a Kolkata-zone write lands on the next calendar day, a Los Angeles one 7–8 h earlier. */
    static final Instant T = Instant.parse("2026-08-14T23:30:00Z");
    static final String T_TEXT = "2026-08-14 23:30:00";
    static final String TICKER = "AAPL";

    private static final DateTimeFormatter SQL_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Autowired MarketPriceProjectionService projectionService;
    @Autowired PortfolioService portfolioService;
    @Autowired PortfolioAnalyticsService analyticsService;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbcTemplate;

    /** The JVM default zone this subclass runs under. */
    abstract String expectedZone();

    @BeforeEach
    void clearTicker() {
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TICKER);
    }

    @Test
    void precondition_jvmAndSessionRunInTheInjectedZone() {
        assertThat(TimeZone.getDefault().toZoneId().normalized())
                .isEqualTo(TimeZone.getTimeZone(expectedZone()).toZoneId().normalized());
        String session = jdbcTemplate.queryForObject("SELECT current_setting('TimeZone')", String.class);
        assertThat(TimeZone.getTimeZone(session).getRawOffset())
                .as("JDBC session zone %s must follow the injected JVM zone %s", session, expectedZone())
                .isEqualTo(TimeZone.getTimeZone(expectedZone()).getRawOffset())
                .isNotZero();
    }

    @Test
    void write_storesUtcWallClockInBothTables() {
        projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), T));

        assertThat(latestText()).isEqualTo(T_TEXT);
        assertThat(historyText()).isEqualTo(T_TEXT);
    }

    @Test
    void replay_ofTheSameObservation_isIdempotent() {
        projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), T));
        int second = projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), T));

        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?", Integer.class, TICKER)).isEqualTo(1);
        assertThat(latestText()).isEqualTo(T_TEXT);
    }

    @Test
    void conflictingPayload_atTheSameObservation_isSurfaced_onTheLatestRowPath() {
        // Latest row only, no history row: only the latest-row readback can see this conflict
        // (a history row at T would let the history path catch it instead).
        jdbcTemplate.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at) "
                        + "VALUES (?, 10.00, 'USD', ?::timestamp, now() AT TIME ZONE 'UTC')",
                TICKER, T_TEXT);

        assertThatThrownBy(() -> projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), T)))
                .isInstanceOf(ObservationConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?", Integer.class, TICKER)).isZero();
    }

    @Test
    void replay_ofTheStoredLatestObservation_isIdempotent_withoutAHistoryRow() {
        jdbcTemplate.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at) "
                        + "VALUES (?, 10.00, 'USD', ?::timestamp, now() AT TIME ZONE 'UTC')",
                TICKER, T_TEXT);

        int rows = projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), T));

        assertThat(rows).isZero();
        assertThat(latestText()).isEqualTo(T_TEXT);
    }

    @Test
    void conflictingPayload_atTheSameObservation_isSurfaced_onTheHistoryPath() {
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) VALUES (?, 'USD', 10.00, ?::timestamp)",
                TICKER, T_TEXT);

        assertThatThrownBy(() -> projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), T)))
                .isInstanceOf(ObservationConflictException.class);
    }

    @Test
    void freshnessReader_returnsTheInstantThatWasStored() {
        String userId = seedUserHolding();
        jdbcTemplate.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at) "
                        + "VALUES (?, 10.00, 'USD', ?::timestamp, now() AT TIME ZONE 'UTC')",
                TICKER, T_TEXT);

        Instant oldest = portfolioService.getSummary(userId)
                .assetPriceFreshness().oldestKnownAssetPriceObservationTimestamp();

        assertThat(oldest).isEqualTo(T);
    }

    @Test
    void change24hReference_isFoundInTheUtcWindow_andReadBackExactly() {
        String userId = seedUserHolding();
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime refUtc = nowUtc.minusHours(24);
        jdbcTemplate.update(
                "INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at) "
                        + "VALUES (?, 110.00, 'USD', ?::timestamp, ?::timestamp)",
                TICKER, SQL_TEXT.format(nowUtc), SQL_TEXT.format(nowUtc));
        jdbcTemplate.update(
                "INSERT INTO market_price_history (ticker, quote_currency, price, observed_at) VALUES (?, 'USD', 100.00, ?::timestamp)",
                TICKER, SQL_TEXT.format(refUtc));
        evictAnalytics(userId);

        PortfolioAnalyticsDto.HoldingAnalyticsDto holding = analyticsService.getAnalytics(userId).holdings().stream()
                .filter(h -> TICKER.equals(h.ticker()))
                .findFirst()
                .orElseThrow();

        // 24 h ago in UTC is inside the 18–36 h window; read in the session zone it would not be.
        assertThat(holding.changeBasis()).isEqualTo("WITHIN_24H_WINDOW");
        assertThat(Instant.parse(holding.change24hReferenceAt())).isEqualTo(refUtc.toInstant(ZoneOffset.UTC));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PriceUpdatedEvent event(BigDecimal price, Instant observedAt) {
        return new PriceUpdatedEvent(TICKER, price, "USD", observedAt, null, null);
    }

    private String latestText() {
        return jdbcTemplate.queryForObject(
                "SELECT observed_at::text FROM market_prices WHERE ticker = ?", String.class, TICKER);
    }

    private String historyText() {
        return jdbcTemplate.queryForObject(
                "SELECT observed_at::text FROM market_price_history WHERE ticker = ?", String.class, TICKER);
    }

    /** A fresh user holding only {@link #TICKER}, so no seeded data leaks into the assertions. */
    private String seedUserHolding() {
        String userId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?::uuid, ?)", userId, userId + "@utc.test");
        String portfolioId = jdbcTemplate.queryForObject(
                "INSERT INTO portfolios (user_id) VALUES (?) RETURNING id::text", String.class, userId);
        jdbcTemplate.update(
                "INSERT INTO asset_holdings (portfolio_id, asset_ticker, quantity) VALUES (?::uuid, ?, 10)",
                portfolioId, TICKER);
        return userId;
    }

    private void evictAnalytics(String userId) {
        var cache = cacheManager.getCache("portfolio-analytics");
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
