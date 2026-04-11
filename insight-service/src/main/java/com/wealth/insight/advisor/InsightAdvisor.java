package com.wealth.insight.advisor;

import com.wealth.insight.dto.PortfolioDto;

/**
 * Domain port for AI-powered portfolio analysis within the insight service.
 *
 * <p>Implementations must be thread-safe. If the portfolio has zero holdings,
 * implementations MUST return an {@link AnalysisResult} with {@code riskScore} 0
 * and empty lists.
 */
public interface InsightAdvisor {

    /**
     * Analyzes the given portfolio and returns risk assessment,
     * concentration warnings, and rebalancing suggestions.
     *
     * @param portfolio the portfolio to analyze (must not be null)
     * @return analysis result (never null)
     * @throws AdvisorUnavailableException if the underlying AI service is unreachable
     */
    AnalysisResult analyze(PortfolioDto portfolio);
}
