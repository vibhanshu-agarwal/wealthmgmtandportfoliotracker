package com.wealth.market.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event published by the Market module whenever an asset price changes.
 *
 * <p>This record is the sole cross-module API surface between {@code com.wealth.market}
 * and its consumers. It is intentionally anemic — a pure data carrier with no behaviour.
 *
 * <p>Delivery guarantee: Spring Modulith's JDBC Event Publication Registry (Outbox Pattern)
 * persists this event to the {@code event_publication} table within the publishing
 * transaction before it is dispatched to listeners.
 *
 * <h2>Backward compatibility</h2>
 * The fields {@code quoteCurrency}, {@code observedAt}, {@code previousReferencePrice}, and
 * {@code previousReferenceAt} were added after the initial 2-field shape. They are
 * <strong>nullable</strong>: old-shape JSON (only {@code ticker} and {@code newPrice}) must
 * continue to deserialize, with the missing new fields resolving to {@code null}. Consumers
 * derive any change/trend from these fields and treat {@code null} as "unavailable" rather
 * than substituting a default. The event itself never precomputes change. A legacy 2-arg
 * constructor is retained so existing producer call sites compile and behave unchanged.
 *
 * <h2>Wire contract invariants</h2>
 * <p>Documented targets for cross-service compatibility; enforcement tests live in
 * {@code portfolio-service} / {@code market-data-service} wire-contract tests (Tasks 6.2/6.5)
 * and the deferred Testcontainers round-trip (Task 6.7).
 * <ul>
 *   <li><strong>Prices</strong> — {@code newPrice} and {@code previousReferencePrice} are
 *       monetary values with scale 2 (two decimal places) on the wire. Producers and contract
 *       tests use {@code BigDecimal} values at that scale; consumers compare with
 *       {@code compareTo}, not {@code equals}, when scale may differ after deserialization.
 *   <li><strong>Timestamps</strong> — {@code observedAt} and {@code previousReferenceAt} are
 *       serialized as ISO-8601 UTC strings (e.g. {@code 2026-06-08T10:15:30Z}), not numeric
 *       epoch timestamps. Millisecond precision is the contract ceiling.
 * </ul>
 *
 * @param ticker                 the asset ticker symbol (e.g. "AAPL", "BTC-USD")
 * @param newPrice               the updated price, expressed in {@code quoteCurrency}
 * @param quoteCurrency          ISO currency the price is quoted in (e.g. "USD"); nullable
 * @param observedAt             the time the price was observed at the source; nullable.
 *                               Never a receive-time fallback — absence means "unknown".
 * @param previousReferencePrice the prior current price rolled forward as a reference point
 *                               for change calculation; nullable
 * @param previousReferenceAt    the observation time of {@code previousReferencePrice}; nullable
 */
public record PriceUpdatedEvent(
        String ticker,
        BigDecimal newPrice,
        String quoteCurrency,
        Instant observedAt,
        BigDecimal previousReferencePrice,
        Instant previousReferenceAt) {

    /**
     * Legacy 2-arg constructor preserved for backward compatibility.
     *
     * <p>Existing producer call sites that only know {@code ticker} and {@code newPrice}
     * continue to compile and behave unchanged; the enrichment fields resolve to {@code null}.
     *
     * @param ticker   the asset ticker symbol
     * @param newPrice the updated price
     */
    public PriceUpdatedEvent(String ticker, BigDecimal newPrice) {
        this(ticker, newPrice, null, null, null, null);
    }
}
