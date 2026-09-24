package com.wealth.insight.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary of a single ticker's market data from the sliding window.
 *
 * @param ticker       the asset ticker symbol
 * @param latestPrice  the most recent price
 * @param priceHistory the last N price points (newest first)
 * @param trendPercent percentage change from oldest to newest in the window,
 *                     or null if fewer than 2 data points exist
 * @param aiSummary    2-sentence AI sentiment analysis, or null if AI unavailable
 * @param quoteCurrency ISO 4217 code {@code latestPrice} and {@code priceHistory} are quoted in,
 *                     from the ticker catalog; null when the ticker is not in the catalog, so a
 *                     client never has to assume USD (rehearsal defect #3)
 */
public record TickerSummary(
        String ticker,
        BigDecimal latestPrice,
        List<BigDecimal> priceHistory,
        BigDecimal trendPercent,
        String aiSummary,
        String quoteCurrency
) {
    /** A summary whose currency is not (yet) known; the controller fills it from the catalog. */
    public TickerSummary(String ticker, BigDecimal latestPrice, List<BigDecimal> priceHistory,
                         BigDecimal trendPercent, String aiSummary) {
        this(ticker, latestPrice, priceHistory, trendPercent, aiSummary, null);
    }

    public TickerSummary withQuoteCurrency(String currency) {
        return new TickerSummary(ticker, latestPrice, priceHistory, trendPercent, aiSummary, currency);
    }
}
