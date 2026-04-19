package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.dto.PortfolioDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static com.wealth.insight.infrastructure.redis.CacheConfig.PORTFOLIO_ANALYSIS_CACHE;

/**
 * AWS Bedrock (Claude 3 Haiku) portfolio advisor — active when the {@code bedrock}
 * Spring profile is enabled (i.e. {@code SPRING_PROFILES_ACTIVE=prod,aws,bedrock} on Lambda).
 *
 * <p>Uses Spring AI {@link ChatClient} backed by the Bedrock Converse API with the same
 * system prompt and {@link AnalysisResult} entity mapping as {@link OllamaInsightAdvisor}.
 *
 * <p>Responses are cached in Redis ({@code portfolio-analysis} cache, 30-minute TTL) to
 * avoid re-running the full LLM analysis on every dashboard refresh. Cache misses (including
 * Redis unavailability) fall through to Bedrock transparently via {@code CacheConfig.errorHandler()}.
 *
 * <p>Empty portfolios short-circuit before any AI call and return a zero-risk result,
 * consistent with the {@link InsightAdvisor} contract.
 */
@Component
@Profile("bedrock")
public class BedrockInsightAdvisor implements InsightAdvisor {

    private static final Logger log = LoggerFactory.getLogger(BedrockInsightAdvisor.class);

    private static final String SYSTEM_PROMPT = """
            You are a wealth management assistant. Analyze the following portfolio holdings \
            and provide a risk score (1-100), concentration warnings, and up to 3 rebalancing \
            suggestions. Respond in JSON format only matching this schema:
            {"riskScore": <int>, "concentrationWarnings": [<string>], "rebalancingSuggestions": [<string>]}
            Do not provide personalised financial advice. Do not recommend specific securities \
            to buy or sell. Limit analysis to portfolio structure, concentration, and diversification.""";

    private final ChatClient chatClient;

    public BedrockInsightAdvisor(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * Analyzes the portfolio and returns risk assessment, concentration warnings,
     * and rebalancing suggestions. Result is cached in Redis for 30 minutes.
     *
     * <p>Empty portfolios return {@code AnalysisResult(0, [], [])} without calling Bedrock.
     */
    @Override
    @Cacheable(value = PORTFOLIO_ANALYSIS_CACHE, key = "#portfolio.id()")
    public AnalysisResult analyze(PortfolioDto portfolio) {
        if (portfolio.holdings().isEmpty()) {
            return new AnalysisResult(0, List.of(), List.of());
        }

        String holdingsText = portfolio.holdings().stream()
                .map(h -> "%s: %s shares".formatted(h.assetTicker(), h.quantity().toPlainString()))
                .collect(Collectors.joining("\n"));

        try {
            AnalysisResult result = chatClient.prompt()
                    .user("Analyze this portfolio:\n" + holdingsText)
                    .call()
                    .entity(AnalysisResult.class);

            if (result == null) {
                throw new AdvisorUnavailableException("Bedrock returned null response");
            }
            return clampRiskScore(result);
        } catch (AdvisorUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Bedrock advisor failed: {}", e.getMessage(), e);
            throw new AdvisorUnavailableException("Bedrock advisor unavailable", e);
        }
    }

    private AnalysisResult clampRiskScore(AnalysisResult result) {
        int clamped = Math.clamp(result.riskScore(), 1, 100);
        if (clamped != result.riskScore()) {
            log.warn("Bedrock returned out-of-range riskScore {}, clamped to {}", result.riskScore(), clamped);
        }
        return new AnalysisResult(clamped, result.concentrationWarnings(), result.rebalancingSuggestions());
    }
}
