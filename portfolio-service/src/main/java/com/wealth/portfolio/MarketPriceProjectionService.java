package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Maintains the portfolio-service read model of latest market prices.
 *
 * <p>Writes are intentionally idempotent so duplicate Kafka delivery does not corrupt the valuation state.
 *
 * <h2>Wave 3 — Task 4.1: history append</h2>
 * In addition to upserting {@code market_prices}, this service now appends a row to
 * {@code market_price_history} for every new {@code (ticker, observed_at)} observation.
 * Idempotency is keyed on {@code (ticker, observed_at)} (the unique index from V13):
 * a duplicate delivery of the same observation is a no-op; a new {@code observed_at}
 * with the same price is a valid new row. Dedup is by observation identity, never by
 * price value.
 *
 * <p>If {@code observedAt} is absent (old-shape event), no history row is written and
 * no synthetic receive-time is substituted — per the "no receive-time fabrication" contract.
 * {@code observedAt} is truncated to millisecond precision before use as the identity key
 * to prevent false duplicates from sub-millisecond precision drift.
 */
@Service
class MarketPriceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceProjectionService.class);

    private final JdbcTemplate jdbcTemplate;

    MarketPriceProjectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    @Transactional
    public void upsertLatestPrice(PriceUpdatedEvent event) {
        // "IS DISTINCT FROM" handles both inequality and null-safe comparison for idempotent updates.
        jdbcTemplate.update(
                """
                INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (ticker) DO UPDATE
                SET current_price  = EXCLUDED.current_price,
                    quote_currency = COALESCE(EXCLUDED.quote_currency, market_prices.quote_currency),
                    updated_at     = EXCLUDED.updated_at
                WHERE market_prices.current_price IS DISTINCT FROM EXCLUDED.current_price
                   OR market_prices.quote_currency IS DISTINCT FROM EXCLUDED.quote_currency
                """,
                event.ticker(),
                event.newPrice(),
                event.quoteCurrency()
        );

        log.info("Market price projection updated for ticker {} -> {}", event.ticker(), event.newPrice());

        // Task 4.1: append to market_price_history for every distinct (ticker, observed_at).
        // If observedAt is absent (old-shape event), do NOT synthesize a receive-time substitute.
        Instant observedAt = event.observedAt();
        if (observedAt == null) {
            log.debug("No observedAt on event for ticker {} — skipping history append", event.ticker());
            return;
        }

        // Normalise to millisecond precision to prevent false duplicates from sub-ms drift.
        Instant observedAtMs = observedAt.truncatedTo(ChronoUnit.MILLIS);

        int historyRows = jdbcTemplate.update(
                """
                INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (ticker, observed_at) DO NOTHING
                """,
                event.ticker(),
                event.quoteCurrency(),
                event.newPrice(),
                java.sql.Timestamp.from(observedAtMs)
        );

        if (historyRows > 0) {
            log.debug("History row appended for ticker {} at {}", event.ticker(), observedAtMs);
        } else {
            log.debug("Idempotent history skip for ticker {} at {} (already exists)", event.ticker(), observedAtMs);
        }
    }
}
