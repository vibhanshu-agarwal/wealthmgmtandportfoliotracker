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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tasks 8.3–8.5: tuple upsert, observation identity, and history conflict against real Postgres.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class MarketPriceProjectionTupleIT {

    private static final String TICKER = "AAPL";
    private static final Instant T1 = Instant.parse("2026-08-10T08:00:00.123Z");
    private static final Instant T2 = Instant.parse("2026-08-11T08:00:00.123Z");

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

    @Autowired
    MarketPriceProjectionService projectionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PriceProjectionSignals signals;

    @BeforeEach
    void resetTicker() {
        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TICKER);
    }

    @Test
    void newerOverKnown_writesTuple() {
        seedLatest(new BigDecimal("10.00"), "USD", T1);

        int rows = projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), "USD", T2));

        assertThat(rows).isEqualTo(1);
        assertLatest(new BigDecimal("11.00"), "USD", T2);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void olderOverKnown_writesNothing() {
        seedLatest(new BigDecimal("10.00"), "USD", T2);

        int rows = projectionService.upsertLatestPrice(event(new BigDecimal("99.00"), "USD", T1));

        assertThat(rows).isEqualTo(0);
        assertLatest(new BigDecimal("10.00"), "USD", T2);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void equalTimestampIdenticalPayload_isIdempotentNoOp() {
        seedLatest(new BigDecimal("10.00"), "USD", T1);
        seedHistory(new BigDecimal("10.00"), "USD", T1);

        int rows = projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), "USD", T1));

        assertThat(rows).isEqualTo(0);
        assertLatest(new BigDecimal("10.00"), "USD", T1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void equalTimestampConflictingPayload_isSurfaced() {
        seedLatest(new BigDecimal("10.00"), "USD", T1);

        assertThatThrownBy(
                        () -> projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), "USD", T1)))
                .isInstanceOf(ObservationConflictException.class);
        assertLatest(new BigDecimal("10.00"), "USD", T1);
    }

    @Test
    void knownOverNull_writesAndAcquiresProvenance() {
        seedLatest(new BigDecimal("10.00"), "USD", null);

        int rows = projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), "USD", T1));

        assertThat(rows).isEqualTo(1);
        assertLatest(new BigDecimal("11.00"), "USD", T1);
    }

    @Test
    void nullOverKnown_writesNothing() {
        seedLatest(new BigDecimal("10.00"), "USD", T1);

        int rows =
                projectionService.upsertLatestPrice(
                        new PriceUpdatedEvent(TICKER, new BigDecimal("99.00"), "USD", null, null, null));

        assertThat(rows).isEqualTo(0);
        assertLatest(new BigDecimal("10.00"), "USD", T1);
        assertThat(historyCount()).isZero();
    }

    @Test
    void nullOverNull_laterReceivedWinsAndEmitsUndatedSignal() {
        seedLatest(new BigDecimal("10.00"), "USD", null);
        long undatedBefore = signals.undatedCount();

        int first =
                projectionService.upsertLatestPrice(
                        new PriceUpdatedEvent(TICKER, new BigDecimal("11.00"), "USD", null, null, null));
        int second =
                projectionService.upsertLatestPrice(
                        new PriceUpdatedEvent(TICKER, new BigDecimal("12.00"), "USD", null, null, null));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertLatest(new BigDecimal("12.00"), "USD", null);
        assertThat(historyCount()).isZero();
        assertThat(signals.undatedCount()).isGreaterThanOrEqualTo(undatedBefore + 2);
    }

    @Test
    void firstInsert_datedAndUndated() {
        int dated = projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), "USD", T1));
        assertThat(dated).isEqualTo(1);
        assertLatest(new BigDecimal("10.00"), "USD", T1);

        jdbcTemplate.update("DELETE FROM market_price_history WHERE ticker = ?", TICKER);
        jdbcTemplate.update("DELETE FROM market_prices WHERE ticker = ?", TICKER);

        int undated =
                projectionService.upsertLatestPrice(
                        new PriceUpdatedEvent(TICKER, new BigDecimal("11.00"), "USD", null, null, null));
        assertThat(undated).isEqualTo(1);
        assertLatest(new BigDecimal("11.00"), "USD", null);
        assertThat(historyCount()).isZero();
    }

    @Test
    void observationIdentity_isTruncatedOnceAndBoundToBothTables() {
        Instant withNanos = T1.plusNanos(999_999);

        int rows =
                projectionService.upsertLatestPrice(event(new BigDecimal("10.00"), "USD", withNanos));

        assertThat(rows).isEqualTo(1);
        assertLatest(new BigDecimal("10.00"), "USD", T1.truncatedTo(ChronoUnit.MILLIS));
        Instant historyObservedAt =
                jdbcTemplate.queryForObject(
                        "SELECT observed_at FROM market_price_history WHERE ticker = ?",
                        Timestamp.class,
                        TICKER)
                        .toInstant();
        assertThat(historyObservedAt).isEqualTo(T1.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void historyConflict_rollsBackLatestRow() {
        seedLatest(new BigDecimal("9.00"), "USD", T1);
        seedHistory(new BigDecimal("10.00"), "USD", T2);

        assertThatThrownBy(
                        () -> projectionService.upsertLatestPrice(event(new BigDecimal("11.00"), "USD", T2)))
                .isInstanceOf(ObservationConflictException.class);

        assertLatest(new BigDecimal("9.00"), "USD", T1);
        Map<String, Object> history =
                jdbcTemplate.queryForMap(
                        "SELECT price, quote_currency FROM market_price_history WHERE ticker = ?", TICKER);
        assertThat((BigDecimal) history.get("price")).isEqualByComparingTo("10.00");
        assertThat(history.get("quote_currency")).isEqualTo("USD");
    }

    private PriceUpdatedEvent event(BigDecimal price, String currency, Instant observedAt) {
        return new PriceUpdatedEvent(TICKER, price, currency, observedAt, null, null);
    }

    private void seedLatest(BigDecimal price, String currency, Instant observedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at)
                VALUES (?, ?, ?, ?, now())
                """,
                TICKER,
                price,
                currency,
                observedAt == null ? null : Timestamp.from(observedAt));
    }

    private void seedHistory(BigDecimal price, String currency, Instant observedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                VALUES (?, ?, ?, ?)
                """,
                TICKER,
                currency,
                price,
                Timestamp.from(observedAt));
    }

    private void assertLatest(BigDecimal price, String currency, Instant observedAt) {
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT current_price, quote_currency, observed_at FROM market_prices WHERE ticker = ?",
                        TICKER);
        assertThat((BigDecimal) row.get("current_price")).isEqualByComparingTo(price);
        assertThat(row.get("quote_currency")).isEqualTo(currency);
        Timestamp stored = (Timestamp) row.get("observed_at");
        if (observedAt == null) {
            assertThat(stored).isNull();
        } else {
            assertThat(stored.toInstant()).isEqualTo(observedAt);
        }
    }

    private int historyCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?", Integer.class, TICKER);
        return count == null ? 0 : count;
    }
}
