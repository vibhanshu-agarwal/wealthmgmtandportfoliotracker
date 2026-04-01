package com.wealth.portfolio.dto;

import java.math.BigDecimal;

public record PortfolioSummaryDto(
        String userId,
        int portfolioCount,
        int totalHoldings,
        BigDecimal totalValue
) {
}
