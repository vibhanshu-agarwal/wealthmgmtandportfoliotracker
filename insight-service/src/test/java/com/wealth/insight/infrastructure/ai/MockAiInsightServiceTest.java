package com.wealth.insight.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiInsightServiceTest {

    private final MockAiInsightService service = new MockAiInsightService();

    @Test
    void getSentiment_returnsStringContainingTickerAndNeutral() {
        String result = service.getSentiment("AAPL");

        assertThat(result).contains("AAPL");
        assertThat(result).contains("Neutral");
    }

    @Test
    void getSentiment_returnsTwoSentences() {
        String result = service.getSentiment("MSFT");

        // Two sentences = at least one period followed by a space and another sentence
        long sentenceCount = result.chars().filter(c -> c == '.').count();
        assertThat(sentenceCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getSentiment_isDeterministic() {
        String first = service.getSentiment("GOOG");
        String second = service.getSentiment("GOOG");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void getSentiment_includesTickerInOutput_forDifferentTickers() {
        assertThat(service.getSentiment("TSLA")).contains("TSLA");
        assertThat(service.getSentiment("AMZN")).contains("AMZN");
        assertThat(service.getSentiment("BTC")).contains("BTC");
    }
}
