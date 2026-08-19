package com.wealth.portfolio.dto;

import java.math.BigDecimal;

/**
 * Summary response for {@code GET /api/portfolio/summary}.
 *
 * @param partialValuation true when one or more holdings were excluded from {@code totalValue}
 *                         (unavailable FX or missing price). Staleness does not set this flag.
 */
public record PortfolioSummaryDto(
        String userId,
        int portfolioCount,
        int totalHoldings,
        BigDecimal totalValue,
        String baseCurrency,
        boolean partialValuation,
        AssetPriceFreshnessDto assetPriceFreshness
) {
    /** Convenience constructor for fully-valued portfolios (backward compat). */
    public PortfolioSummaryDto(String userId, int portfolioCount, int totalHoldings,
                               BigDecimal totalValue, String baseCurrency) {
        this(userId, portfolioCount, totalHoldings, totalValue, baseCurrency, false,
                AssetPriceFreshnessDto.emptyPortfolio());
    }

    public PortfolioSummaryDto(String userId, int portfolioCount, int totalHoldings,
                               BigDecimal totalValue, String baseCurrency, boolean partialValuation) {
        this(userId, portfolioCount, totalHoldings, totalValue, baseCurrency, partialValuation,
                AssetPriceFreshnessDto.emptyPortfolio());
    }
}
