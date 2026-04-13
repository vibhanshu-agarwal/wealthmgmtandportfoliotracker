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
 */
public record TickerSummary(
        String ticker,
        BigDecimal latestPrice,
        List<BigDecimal> priceHistory,
        BigDecimal trendPercent,
        String aiSummary
) {}
