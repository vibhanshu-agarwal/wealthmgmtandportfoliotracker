package com.wealth.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Top-level analytics response DTO for {@code GET /api/portfolio/analytics}.
 * All monetary values are expressed in {@code baseCurrency}.
 */
public record PortfolioAnalyticsDto(
        BigDecimal totalValue,
        BigDecimal totalCostBasis,
        BigDecimal totalUnrealizedPnL,
        BigDecimal totalUnrealizedPnLPercent,
        String baseCurrency,
        PerformerDto bestPerformer,
        PerformerDto worstPerformer,
        List<HoldingAnalyticsDto> holdings,
        List<PerformancePointDto> performanceSeries
) {

    /**
     * Identifies the best or worst performing holding by 24h price change.
     */
    public record PerformerDto(
            String ticker,
            BigDecimal change24hPercent
    ) {}

    /**
     * Per-holding analytics snapshot, all values FX-converted to {@code baseCurrency}.
     */
    public record HoldingAnalyticsDto(
            String ticker,
            BigDecimal quantity,
            BigDecimal currentPrice,
            /* FX-converted total value in baseCurrency. */
            BigDecimal currentValueBase,
            /* Placeholder: equals currentPrice until trade ledger is available. */
            BigDecimal avgCostBasis,
            /* currentValueBase - (quantity × avgCostBasis × fxRate). Always 0 with placeholder. */
            BigDecimal unrealizedPnL,
            /* currentPrice - price24hAgo (in quoteCurrency). */
            BigDecimal change24hAbsolute,
            /* (change24hAbsolute / price24hAgo) × 100, scaled to 4 d.p. */
            BigDecimal change24hPercent,
            String quoteCurrency
    ) {}

    /**
     * A single data point in the historical performance series.
     */
    public record PerformancePointDto(
            /* ISO-8601 date string, e.g. "2024-03-01". */
            String date,
            /* Total portfolio value on this date in baseCurrency. */
            BigDecimal value,
            /* Day-over-day change in baseCurrency. Zero for the first point. */
            BigDecimal change
    ) {}
}
