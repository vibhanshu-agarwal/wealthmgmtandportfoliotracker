package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.dto.TickerSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ollama-backed market sentiment adapter — active when the {@code ollama}
 * Spring profile is enabled. Uses Spring AI {@link ChatClient} with phi3
 * to generate 2-sentence sentiment analyses per ticker.
 */
@Service
@Profile("ollama")
public class OllamaAiInsightService implements AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiInsightService.class);

    private static final String SYSTEM_PROMPT = """
            You are a market analyst. Given a ticker symbol, its recent price history, \
            and trend percentage, provide exactly 2 sentences: first categorize the sentiment \
            as Bullish, Bearish, or Neutral, then briefly explain why based on the data. \
            Respond in plain text only.""";

    private final ChatClient chatClient;
    private final MarketDataService marketDataService;

    public OllamaAiInsightService(ChatClient.Builder chatClientBuilder,
                                  MarketDataService marketDataService) {
        this.chatClient = chatClientBuilder.build();
        this.marketDataService = marketDataService;
    }

    @Override
    public String getSentiment(String ticker) {
        TickerSummary summary = marketDataService.getTickerSummary(ticker);
        String userPrompt = buildPrompt(ticker, summary);

        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                throw new AdvisorUnavailableException("LLM returned empty response for " + ticker);
            }
            return response;
        } catch (AdvisorUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Ollama sentiment analysis failed for {}: {}", ticker, e.getMessage(), e);
            throw new AdvisorUnavailableException("LLM unavailable for " + ticker, e);
        }
    }

    String buildPrompt(String ticker, TickerSummary summary) {
        String prices = "no data";
        if (summary.priceHistory() != null && !summary.priceHistory().isEmpty()) {
            prices = summary.priceHistory().stream()
                    .map(BigDecimal::toPlainString)
                    .collect(Collectors.joining(", "));
        }

        String trend = summary.trendPercent() != null
                ? summary.trendPercent().toPlainString() + "%"
                : "N/A";

        return "Ticker: %s\nRecent prices (newest first): [%s]\nTrend: %s".formatted(ticker, prices, trend);
    }
}
