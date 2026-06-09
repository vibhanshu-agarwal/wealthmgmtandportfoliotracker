package com.wealth.portfolio.dto;

import java.math.BigDecimal;

/**
 * Summary response for {@code GET /api/portfolio/summary}.
 *
 * @param partialValuation true when one or more holdings were excluded from {@code totalValue}
 *                         because their FX rate was unavailable at computation time.
 *                         Consumers should label the total as partial/approximate.
 */
public record PortfolioSummaryDto(
        String userId,
        int portfolioCount,
        int totalHoldings,
        BigDecimal totalValue,
        String baseCurrency,
        boolean partialValuation
) {
    /** Convenience constructor for fully-valued portfolios (backward compat). */
    public PortfolioSummaryDto(String userId, int portfolioCount, int totalHoldings,
                               BigDecimal totalValue, String baseCurrency) {
        this(userId, portfolioCount, totalHoldings, totalValue, baseCurrency, false);
    }
}
