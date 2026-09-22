package com.wealth.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Top-level analytics response DTO for {@code GET /api/portfolio/analytics}.
 * All monetary values are expressed in {@code baseCurrency}.
 *
 * <p>Task 5 additions:
 * <ul>
 *   <li>{@code totalUnrealizedPnL} / {@code totalUnrealizedPnLPercent} — nullable; absent when
 *       no holdings have a recorded cost basis (never coerced to 0).</li>
 *   <li>{@code performanceCoverage} — coverage metadata for the performance series.</li>
 * </ul>
 *
 * <p>D11 additions (finding F13): {@code totalChange24hBase} / {@code totalChange24hPercent} and
 * the per-holding {@code change24hValueBase} — the 24h change as a position amount in
 * {@code baseCurrency}. {@code change24hAbsolute} stays a per-unit, quote-currency price change.
 *
 * @param partialValuation true when one or more holdings were excluded from aggregates because
 *                         their FX rate was unavailable. Consumers should label totals as partial.
 * @param totalUnrealizedPnL nullable; null means cost basis data is absent for all holdings
 * @param totalUnrealizedPnLPercent nullable; null when totalUnrealizedPnL is null
 * @param totalChange24hBase nullable; Σ {@code change24hValueBase} over the holdings counted in
 *                           {@code totalValue}; null when none of them has a 24h change
 * @param totalChange24hPercent nullable; {@code totalChange24hBase} as a percentage of those
 *                              holdings' value at the reference; null when that value is not positive
 * @param change24hCoverage which holdings the 24h totals cover; never null. Consumers must disclose
 *                          a partial total rather than present it as the whole portfolio's change.
 * @param performanceCoverage coverage metadata for the performance series; never null
 */
public record PortfolioAnalyticsDto(
        BigDecimal totalValue,
        BigDecimal totalCostBasis,
        BigDecimal totalUnrealizedPnL,
        BigDecimal totalUnrealizedPnLPercent,
        BigDecimal totalChange24hBase,
        BigDecimal totalChange24hPercent,
        Change24hCoverageDto change24hCoverage,
        String baseCurrency,
        boolean partialValuation,
        PerformerDto bestPerformer,
        PerformerDto worstPerformer,
        List<HoldingAnalyticsDto> holdings,
        List<PerformancePointDto> performanceSeries,
        PerformanceCoverageDto performanceCoverage
) {

    /**
     * D11: coverage of {@code totalChange24hBase} / {@code totalChange24hPercent}.
     *
     * @param holdingsWithChange holdings that contribute a position-level 24h change to the totals
     * @param countedHoldings    holdings counted in {@code totalValue}
     * @param totalHoldings      every holding in the portfolio, including those without a price or
     *                           FX rate, so a missing price, FX rate or history always shows
     * @param partial            {@code holdingsWithChange < totalHoldings}
     */
    public record Change24hCoverageDto(
            int holdingsWithChange,
            int countedHoldings,
            int totalHoldings,
            boolean partial
    ) {}

    /**
     * Identifies the best or worst performing holding by 24h price change.
     * {@code change24hPercent} is null when no in-window reference price exists.
     */
    public record PerformerDto(
            String ticker,
            BigDecimal change24hPercent
    ) {}

    /**
     * Per-holding analytics snapshot, all monetary values FX-converted to {@code baseCurrency}.
     *
     * <p>Task 5 semantics — nullable fields indicate "unavailable", never a real zero:
     * <ul>
     *   <li>{@code currentPrice} — null when no {@code market_prices} row exists for the ticker
     *       (holding is tracked but price feed is absent or has not yet refreshed).</li>
     *   <li>{@code currentValueBase} — null when {@code currentPrice} is null OR the quote-currency
     *       FX rate is unavailable. Never {@code 0} for a missing price.</li>
     *   <li>{@code unrealizedPnL} / {@code unrealizedPnLPercent} — null when
     *       {@code avgCostBasis} is absent or {@code currentValueBase} is null.</li>
     *   <li>{@code change24hAbsolute} / {@code change24hPercent} / {@code change24hReferenceAt}
     *       / {@code changeBasis} — null when no history row falls within the tolerance window
     *       or when {@code currentPrice} is null.</li>
     *   <li>{@code displayAssetClass} — canonical UI display class (never null; defaults to
     *       {@code "OTHER"} for unknown tickers).</li>
     * </ul>
     */
    public record HoldingAnalyticsDto(
            String ticker,
            BigDecimal quantity,
            /** Current market price per unit in quoteCurrency; null when no price row exists. */
            BigDecimal currentPrice,
            /** FX-converted total value in baseCurrency; null when price or FX rate unavailable. */
            BigDecimal currentValueBase,
            /** Average cost per unit in {@code costBasisCurrency}; null = unavailable. */
            BigDecimal avgCostBasis,
            /** ISO currency of avgCostBasis (may differ from quoteCurrency); null when basis absent. */
            String costBasisCurrency,
            /** FX-converted unrealised P&L in baseCurrency; null when basis absent. */
            BigDecimal unrealizedPnL,
            /** Unrealised return as a percentage; null when basis absent. */
            BigDecimal unrealizedPnLPercent,
            /** Absolute price change from reference (in quoteCurrency); null = no reference. */
            BigDecimal change24hAbsolute,
            /** Percentage change from reference; null = no reference. */
            BigDecimal change24hPercent,
            /**
             * D11: the position's 24h change in baseCurrency, {@code quantity × (currentPrice −
             * reference) × current FX rate}; null when the change or the FX rate is unavailable.
             */
            BigDecimal change24hValueBase,
            /**
             * ISO-8601 timestamp of the reference price used for change calculation; null = no reference.
             * Use this to label the change basis accurately in the UI.
             */
            String change24hReferenceAt,
            /**
             * How the change was labelled: {@code "WITHIN_24H_WINDOW"} when the reference falls in
             * the ≈18–36h tolerance window; {@code "SINCE_PREVIOUS_SNAPSHOT"} otherwise;
             * null when no reference exists.
             */
            String changeBasis,
            String quoteCurrency,
            /**
             * Canonical UI display asset class: {@code STOCK}, {@code CRYPTO}, {@code BOND},
             * {@code CASH}, {@code COMMODITY}, or {@code OTHER}.
             */
            String displayAssetClass
    ) {}

    /**
     * A single data point in the historical performance series.
     */
    public record PerformancePointDto(
            /** ISO-8601 date string, e.g. "2024-03-01". */
            String date,
            /** Total portfolio value on this date in baseCurrency. */
            BigDecimal value,
            /** Day-over-day change in baseCurrency. Zero for the first point. */
            BigDecimal change
    ) {}

    /**
     * Coverage metadata for the performance series.
     *
     * <p>When {@code partial} is true, the series covers only a subset of holdings and consumers
     * must NOT present it as full-portfolio performance.
     *
     * @param holdingsWithHistory number of holdings that had at least one history row in the period
     * @param totalHoldings       total number of holdings in the portfolio
     * @param partial             true when holdingsWithHistory &lt; totalHoldings, or when the series
     *                            is synthetic (no real history data available)
     * @param synthetic           true when the series was generated as a placeholder (no real data)
     */
    public record PerformanceCoverageDto(
            int holdingsWithHistory,
            int totalHoldings,
            boolean partial,
            boolean synthetic
    ) {}
}
