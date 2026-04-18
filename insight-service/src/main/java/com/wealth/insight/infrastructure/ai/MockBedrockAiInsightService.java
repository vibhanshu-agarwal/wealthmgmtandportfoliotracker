package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Structural mock for Amazon Bedrock — active under the {@code bedrock} profile.
 *
 * <p>Does NOT use the actual Spring AI Bedrock dependency. Logs a deferral message
 * and returns a randomized dummy sentiment string. This serves as a placeholder
 * until the live Bedrock integration is implemented.
 */
@Service
@Profile("bedrock")
public class MockBedrockAiInsightService implements AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(MockBedrockAiInsightService.class);

    private static final List<String> SENTIMENTS = List.of("Bullish", "Bearish", "Neutral");

    @Override
    public String getSentiment(String ticker) {
        log.info("Bedrock integration deferred — returning mock sentiment for {}", ticker);
        String sentiment = SENTIMENTS.get(ThreadLocalRandom.current().nextInt(SENTIMENTS.size()));
        return "Mock Bedrock: %s sentiment is currently %s. Live Bedrock analysis is pending deployment.".formatted(ticker, sentiment);
    }
}
