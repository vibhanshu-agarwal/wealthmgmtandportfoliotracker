package com.wealth.insight.infrastructure.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.wealth.insight.AiInsightService;

/**
 * Default mock adapter — active whenever the {@code bedrock} profile is not enabled.
 * Returns deterministic, hardcoded Neutral sentiment with zero latency and no
 * network calls. Used for local development, CI, and any environment where the
 * real Bedrock adapter is not wired.
 */
@Service
@Profile("!bedrock")
public class MockAiInsightService implements AiInsightService {

    @Override
    public String getSentiment(String ticker) {
        return "%s is showing Neutral sentiment. No significant price movement detected.".formatted(ticker);
    }
}
