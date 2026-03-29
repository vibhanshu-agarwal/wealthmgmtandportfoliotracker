package com.wealth.market.events;

import java.math.BigDecimal;

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
 * @param ticker   the asset ticker symbol (e.g. "AAPL", "BTC")
 * @param newPrice the updated price
 */
public record PriceUpdatedEvent(String ticker, BigDecimal newPrice) {}
