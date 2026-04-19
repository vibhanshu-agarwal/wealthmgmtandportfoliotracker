package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.dto.TickerSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.stream.Collectors;

import static com.wealth.insight.infrastructure.redis.CacheConfig.SENTIMENT_CACHE;

/**
 * AWS Bedrock (Claude 3 Haiku) market sentiment adapter — active when the {@code bedrock}
 * Spring profile is enabled (i.e. {@code SPRING_PROFILES_ACTIVE=prod,aws,bedrock} on Lambda).
 *
 * <p>Replaces {@link MockBedrockAiInsightService} which was a placeholder stub.
 * Uses Spring AI {@link ChatClient} backed by the Bedrock Converse API — the same
 * abstraction as the Ollama adapter, just with an AWS-native provider.
 *
 * <p>Responses are cached in Redis ({@code sentiment} cache, 60-minute TTL) to avoid
 * redundant Bedrock API calls for repeated ticker lookups. Cache misses (including Redis
 * unavailability) fall through to Bedrock transparently via {@code CacheConfig.errorHandler()}.
 *
 * <p>Model: {@code anthropic.claude-3-haiku-20240307-v1:0} (us-east-1).
 * Claude 3 Haiku is ~10x cheaper than Sonnet with comparable quality for sentiment analysis.
 * No API key required — the Lambda execution role's IAM credentials are used automatically.
 */
@Service
@Profile("bedrock")
public class BedrockAiInsightService implements AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(BedrockAiInsightService.class);

    private static final String SYSTEM_PROMPT = """
            You are a market analyst. Given a ticker symbol, its recent price history, \
            and trend percentage, provide exactly 2 sentences: first categorize the sentiment \
            as Bullish, Bearish, or Neutral, then briefly explain why based on the data. \
            Respond in plain text only.""";

    private final ChatClient chatClient;
    private final MarketDataService marketDataService;

    public BedrockAiInsightService(ChatClient.Builder chatClientBuilder,
                                   MarketDataService marketDataService) {
        this.chatClient = chatClientBuilder.build();
        this.marketDataService = marketDataService;
    }

    /**
     * Returns a 2-sentence sentiment analysis for the given ticker.
     * Result is cached in Redis for 60 minutes (see {@code CacheConfig}).
     */
    @Override
    @Cacheable(value = SENTIMENT_CACHE, key = "#ticker")
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
                throw new AdvisorUnavailableException("Bedrock returned empty response for " + ticker);
            }
            return response;
        } catch (AdvisorUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Bedrock sentiment analysis failed for {}: {}", ticker, e.getMessage(), e);
            throw new AdvisorUnavailableException("Bedrock unavailable for " + ticker, e);
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
