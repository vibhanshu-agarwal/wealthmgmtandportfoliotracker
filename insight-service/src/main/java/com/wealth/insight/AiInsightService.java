package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;

/**
 * Domain port for AI-powered market sentiment analysis.
 *
 * <p>Returns a 2-sentence plain-text sentiment summary for a given ticker.
 * Profile-scoped adapters provide the concrete implementation:
 * mock (default / local / CI) or Bedrock ({@code bedrock} profile — AWS cloud).
 *
 * <p>Distinct from {@link com.wealth.insight.advisor.InsightAdvisor},
 * which handles portfolio-level risk analysis.
 */
public interface AiInsightService {

    /**
     * Returns a 2-sentence sentiment analysis for the given ticker.
     *
     * @param ticker the ticker symbol (e.g., "AAPL")
     * @return plain-text sentiment summary
     * @throws AdvisorUnavailableException if the LLM is unreachable or returns an empty response
     */
    String getSentiment(String ticker);
}
