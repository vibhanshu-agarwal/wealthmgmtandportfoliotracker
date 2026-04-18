package com.wealth.insight.advisor;

import java.util.List;

/**
 * Immutable result of a portfolio analysis.
 *
 * @param riskScore               risk level 1–100 (0 for empty portfolios)
 * @param concentrationWarnings   warnings about over-concentrated positions
 * @param rebalancingSuggestions   actionable rebalancing suggestions (max 3)
 */
public record AnalysisResult(
        int riskScore,
        List<String> concentrationWarnings,
        List<String> rebalancingSuggestions
) {
    public AnalysisResult {
        concentrationWarnings = List.copyOf(concentrationWarnings);
        rebalancingSuggestions = List.copyOf(rebalancingSuggestions);
    }
}
