package com.wealth.portfolio;

import java.math.BigDecimal;

/**
 * Internal projection returned by the analytics SQL query.
 * Not a public DTO — never serialised to HTTP responses.
 *
 * <p>The query returns two row types distinguished by {@code rowType}:
 * <ul>
 *   <li>{@code "HOLDING"} — one row per holding with current price and 24h-ago price.</li>
 *   <li>{@code "HISTORY"} — one row per (ticker, date) from {@code market_price_history}.</li>
 * </ul>
 * Fields irrelevant to a given row type are {@code null}.
 */
record AnalyticsQueryRow(
        String rowType,
        String assetTicker,
        BigDecimal quantity,        // HOLDING rows only
        BigDecimal currentPrice,    // HOLDING rows only
        String quoteCurrency,
        BigDecimal price24hAgo,     // HOLDING rows only; null if no history row exists
        String historyDate,         // HISTORY rows only; "YYYY-MM-DD"
        BigDecimal historyPrice     // HISTORY rows only
) {}
