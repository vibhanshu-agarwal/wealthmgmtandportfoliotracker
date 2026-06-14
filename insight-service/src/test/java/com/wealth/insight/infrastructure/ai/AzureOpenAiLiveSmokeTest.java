package com.wealth.insight.infrastructure.ai;

import com.wealth.InsightApplication;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live smoke test that invokes a real Azure OpenAI / Foundry deployment through the
 * merged {@code spring-ai-openai} module ({@code spring.ai.openai.*} properties).
 *
 * <p><b>Disabled by default.</b> This is the wire-smoke gate for Tasks 8.1, 8.2, 8.6, and
 * checkpoint 9. After a successful run, record in
 * {@code .kiro/specs/springboot-41-springai-2-migration/spike-azure-openai-auth.md}:
 * <ul>
 *   <li>HTTP status (expect 200)</li>
 *   <li>URL path shape (deployment in path, {@code api-version} query param)</li>
 *   <li>Auth header type ({@code Authorization: Bearer} vs {@code api-key}) — redact token values</li>
 *   <li>Whether {@code azure-identity} / {@code DefaultAzureCredential} or {@code openai-java}'s
 *       own credential supplier was observed</li>
 * </ul>
 *
 * <p><b>Managed Identity / Entra path (preferred):</b>
 * <pre>
 *   RUN_AZURE_OPENAI_LIVE_TESTS=true
 *   AZURE_OPENAI_ENDPOINT=https://&lt;resource&gt;.openai.azure.com/
 *   AZURE_OPENAI_DEPLOYMENT=gpt-4o-mini
 *   # local: az login (or ACA system-assigned MI in non-prod)
 *   # do NOT set OPENAI_API_KEY
 * </pre>
 *
 * <p><b>API-key comparison path (isolate auth vs routing failures only):</b>
 * <pre>
 *   OPENAI_API_KEY=&lt;key&gt;
 * </pre>
 *
 * <p>Invoke:
 * <pre>
 *   ./gradlew :insight-service:test --tests AzureOpenAiLiveSmokeTest
 * </pre>
 */
@Tag("azure-live")
@EnabledIfEnvironmentVariable(
        named = "RUN_AZURE_OPENAI_LIVE_TESTS",
        matches = "true",
        disabledReason = "Set RUN_AZURE_OPENAI_LIVE_TESTS=true plus AZURE_OPENAI_ENDPOINT to run the live Azure OpenAI smoke test."
)
@SpringBootTest(
        classes = InsightApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cache.type=none",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.kafka.bootstrap-servers=localhost:0",
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles({"default", "azure-ai"})
class AzureOpenAiLiveSmokeTest {

    @Autowired
    private InsightAdvisor insightAdvisor;

    @Test
    void analyze_diversifiedPortfolio_returnsValidStructuredAnalysisFromAzureOpenAi() {
        PortfolioDto portfolio = new PortfolioDto(
                UUID.randomUUID(),
                "azure-live-smoke-test-user",
                Instant.now(),
                List.of(
                        new PortfolioHoldingDto(UUID.randomUUID(), "AAPL", new BigDecimal("50")),
                        new PortfolioHoldingDto(UUID.randomUUID(), "MSFT", new BigDecimal("30")),
                        new PortfolioHoldingDto(UUID.randomUUID(), "BND",  new BigDecimal("100"))
                )
        );

        assertThat(insightAdvisor)
                .as("azure-ai profile must wire AzureOpenAiInsightAdvisor, not MockInsightAdvisor")
                .isInstanceOf(AzureOpenAiInsightAdvisor.class);

        AnalysisResult result = insightAdvisor.analyze(portfolio);

        assertThat(result).isNotNull();
        assertThat(result.riskScore())
                .as("Azure OpenAI must return a risk score in the clamped 1–100 range")
                .isBetween(1, 100);
        assertThat(result.concentrationWarnings()).isNotNull();
        assertThat(result.rebalancingSuggestions())
                .as("system prompt caps suggestions at 3")
                .hasSizeLessThanOrEqualTo(3);
    }
}
