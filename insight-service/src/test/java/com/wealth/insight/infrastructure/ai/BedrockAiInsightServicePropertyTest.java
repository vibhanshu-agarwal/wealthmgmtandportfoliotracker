package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.dto.TickerSummary;
import org.junit.jupiter.api.RepeatedTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4: Prompt construction includes all required data.
 *
 * <p>For any ticker and TickerSummary with non-null price data,
 * the prompt contains the ticker symbol, at least one price, and the trend percent.
 *
 * <p>Migrated from the removed OllamaAiInsightServicePropertyTest; the exact same
 * {@code buildPrompt} contract is exercised here against the surviving Bedrock adapter.
 */
class BedrockAiInsightServicePropertyTest {

    /**
     * We can test buildPrompt directly since it's package-private.
     * We instantiate via a subclass to avoid needing ChatClient/MarketDataService.
     */
    @RepeatedTest(100)
    void buildPrompt_alwaysContainsTickerAndPriceAndTrend() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomTicker(rng);
        List<BigDecimal> prices = randomPriceHistory(rng);
        BigDecimal trend = BigDecimal.valueOf(rng.nextDouble(-50, 50))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        TickerSummary summary = new TickerSummary(ticker, prices.getFirst(), prices, trend, null);

        String prompt = new TestableBedrockAiInsightService().buildPrompt(ticker, summary);

        assertThat(prompt).contains(ticker);
        assertThat(prompt).contains(prices.getFirst().toPlainString());
        assertThat(prompt).contains(trend.toPlainString());
    }

    @RepeatedTest(100)
    void buildPrompt_withNullTrend_containsNA() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomTicker(rng);
        BigDecimal price = BigDecimal.valueOf(rng.nextDouble(1, 1000));

        TickerSummary summary = new TickerSummary(ticker, price, List.of(price), null, null);

        String prompt = new TestableBedrockAiInsightService().buildPrompt(ticker, summary);

        assertThat(prompt).contains(ticker);
        assertThat(prompt).contains("N/A");
    }

    private static String randomTicker(ThreadLocalRandom rng) {
        String[] tickers = {"AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "NVDA", "META", "NFLX"};
        return tickers[rng.nextInt(tickers.length)];
    }

    private static List<BigDecimal> randomPriceHistory(ThreadLocalRandom rng) {
        int size = rng.nextInt(1, 11);
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            prices.add(BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0)));
        }
        return prices;
    }

    /**
     * Minimal subclass that exposes buildPrompt without requiring real dependencies.
     * Uses a mock ChatClient.Builder to avoid NPE in the constructor.
     */
    static class TestableBedrockAiInsightService extends BedrockAiInsightService {
        TestableBedrockAiInsightService() {
            super(org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS), null);
        }
    }
}
