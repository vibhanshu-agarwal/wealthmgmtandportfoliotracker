package com.wealth.insight.infrastructure.ai;

import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5: Mock sentiment contains ticker and Neutral category.
 *
 * <p>For any non-null ticker symbol string, MockAiInsightService.getSentiment()
 * returns a string containing the ticker symbol and the word "Neutral".
 */
class MockAiInsightServicePropertyTest {

    private final MockAiInsightService service = new MockAiInsightService();

    @RepeatedTest(100)
    void getSentiment_anyTicker_containsTickerAndNeutral() {
        String ticker = randomTicker();

        String result = service.getSentiment(ticker);

        assertThat(result).contains(ticker);
        assertThat(result).contains("Neutral");
    }

    private static String randomTicker() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int len = rng.nextInt(1, 6);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('A' + rng.nextInt(26)));
        }
        return sb.toString();
    }
}
