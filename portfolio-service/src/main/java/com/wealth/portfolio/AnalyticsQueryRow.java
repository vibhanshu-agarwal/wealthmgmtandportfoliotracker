package com.wealth.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal projection returned by the analytics SQL query.
 * Not a public DTO — never serialised to HTTP responses.
 *
 * <p>The query returns two row types distinguished by {@code rowType}:
 * <ul>
 *   <li>{@code "HOLDING"} — one row per holding with current price, 24h-window reference
 *       price/timestamp, and cost-basis fields.</li>
 *   <li>{@code "HISTORY"} — one row per (ticker, date) from {@code market_price_history}.</li>
 * </ul>
 * Fields irrelevant to a given row type are {@code null}.
 *
 * <p>Task 5 additions:
 * <ul>
 *   <li>{@code avgCostBasis} / {@code costBasisCurrency} — nullable cost-basis from
 *       {@code asset_holdings}; absent means P&amp;L is unavailable (never 0).</li>
 *   <li>{@code price24hReferenceAt} — the {@code observed_at} of the row that provided
 *       {@code price24hAgo}, so the service can label the change basis accurately.</li>
 * </ul>
 */
record AnalyticsQueryRow(
        String rowType,
        String assetTicker,
        BigDecimal quantity,             // HOLDING rows only
        BigDecimal currentPrice,         // HOLDING rows only
        String quoteCurrency,
        BigDecimal price24hAgo,          // HOLDING rows only; null if no in-window history row
        Instant price24hReferenceAt,     // HOLDING rows only; observed_at of the reference row; null if no reference
        BigDecimal avgCostBasis,         // HOLDING rows only; null = cost basis unavailable
        String costBasisCurrency,        // HOLDING rows only; ISO currency of avgCostBasis; null when basis absent
        String historyDate,              // HISTORY rows only; "YYYY-MM-DD"
        BigDecimal historyPrice          // HISTORY rows only
) {}
