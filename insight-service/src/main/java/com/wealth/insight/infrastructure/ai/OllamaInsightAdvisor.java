package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.dto.PortfolioDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ollama-backed advisor — active when the {@code ollama} Spring profile is enabled.
 * Uses Spring AI {@link ChatClient} with a system prompt constraining the model
 * to structural analysis only.
 */
@Component
@Profile("ollama")
public class OllamaInsightAdvisor implements InsightAdvisor {

    private static final Logger log = LoggerFactory.getLogger(OllamaInsightAdvisor.class);

    private static final String SYSTEM_PROMPT = """
            You are a wealth management assistant. Analyze the following portfolio holdings \
            and provide a risk score (1-100), concentration warnings, and up to 3 rebalancing \
            suggestions. Respond in JSON format only matching this schema:
            {"riskScore": <int>, "concentrationWarnings": [<string>], "rebalancingSuggestions": [<string>]}
            Do not provide personalised financial advice. Do not recommend specific securities \
            to buy or sell. Limit analysis to portfolio structure, concentration, and diversification.""";

    private final ChatClient chatClient;

    public OllamaInsightAdvisor(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
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
                throw new AdvisorUnavailableException("Ollama returned null response");
            }
            return clampRiskScore(result);
        } catch (AdvisorUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Ollama advisor failed: {}", e.getMessage(), e);
            throw new AdvisorUnavailableException("Ollama advisor unavailable", e);
        }
    }

    private AnalysisResult clampRiskScore(AnalysisResult result) {
        int clamped = Math.clamp(result.riskScore(), 1, 100);
        if (clamped != result.riskScore()) {
            log.warn("Ollama returned out-of-range riskScore {}, clamped to {}", result.riskScore(), clamped);
        }
        return new AnalysisResult(clamped, result.concentrationWarnings(), result.rebalancingSuggestions());
    }
}
