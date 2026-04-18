package com.wealth.insight.infrastructure.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.wealth.insight.AiInsightService;

/**
 * Default mock adapter — active when neither {@code ollama} nor {@code bedrock}
 * profiles are enabled. Returns deterministic, hardcoded Neutral sentiment
 * with zero latency and no network calls.
 */
@Service
@Profile("!ollama & !bedrock")
public class MockAiInsightService implements AiInsightService {

    @Override
    public String getSentiment(String ticker) {
        return "%s is showing Neutral sentiment. No significant price movement detected.".formatted(ticker);
    }
}
