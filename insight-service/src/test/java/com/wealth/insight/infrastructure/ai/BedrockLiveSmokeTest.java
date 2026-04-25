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
 * Opt-in live smoke test that invokes the real AWS Bedrock Claude Haiku 4.5
 * cross-region inference profile ({@code us.anthropic.claude-haiku-4-5-20251001-v1:0}).
 *
 * <p><b>Disabled by default.</b> To run, set:
 * <pre>
 *   RUN_BEDROCK_LIVE_TESTS=true
 *   AWS_ACCESS_KEY_ID=...
 *   AWS_SECRET_ACCESS_KEY=...
 *   AWS_REGION=us-east-1
 * </pre>
 * …then invoke:
 * <pre>
 *   ./gradlew :insight-service:test --tests BedrockLiveSmokeTest
 * </pre>
 *
 * <p><b>What this test validates end-to-end:</b>
 * <ol>
 *   <li>The {@code bedrock} Spring profile wires {@link BedrockInsightAdvisor} (not the mock).</li>
 *   <li>Spring AI's Bedrock Converse auto-configuration builds a {@code ChatClient.Builder}
 *       using the model ID from {@code application-bedrock.yml}
 *       ({@code spring.ai.bedrock.converse.chat.options.model}).</li>
 *   <li>AWS accepts an {@code InvokeModel} call against the
 *       {@code us.anthropic.claude-haiku-4-5-20251001-v1:0} inference profile with the
 *       configured credentials — i.e. the IAM policy + model access are correctly granted.</li>
 *   <li>Haiku 4.5 returns a JSON payload that Spring AI can bind into {@link AnalysisResult}
 *       via the {@code entity(Class)} converter.</li>
 * </ol>
 *
 * <p>Runs the advisor (not the sentiment service) because the advisor only requires a
 * {@code ChatClient.Builder} — no portfolio-service REST client, no Kafka, no Redis cache
 * needed to exercise the Bedrock code path. Cache is disabled via {@code spring.cache.type=none}
 * to keep the test hermetic.
 *
 * <p>Cost: ~$0.0001 per run (Haiku 4.5 input tokens for a short prompt).
 */
@Tag("bedrock-live")
@EnabledIfEnvironmentVariable(
        named = "RUN_BEDROCK_LIVE_TESTS",
        matches = "true",
        disabledReason = "Set RUN_BEDROCK_LIVE_TESTS=true plus AWS credentials to run the live Bedrock smoke test."
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
@ActiveProfiles({"default", "bedrock"})
class BedrockLiveSmokeTest {

    @Autowired
    private InsightAdvisor insightAdvisor;

    @Test
    void analyze_diversifiedPortfolio_returnsValidStructuredAnalysisFromHaiku45() {
        PortfolioDto portfolio = new PortfolioDto(
                UUID.randomUUID(),
                "bedrock-live-smoke-test-user",
                Instant.now(),
                List.of(
                        new PortfolioHoldingDto(UUID.randomUUID(), "AAPL", new BigDecimal("50")),
                        new PortfolioHoldingDto(UUID.randomUUID(), "MSFT", new BigDecimal("30")),
                        new PortfolioHoldingDto(UUID.randomUUID(), "BND",  new BigDecimal("100"))
                )
        );

        // Proves the bedrock profile activated the real Bedrock advisor, not the mock.
        assertThat(insightAdvisor)
                .as("bedrock profile must wire BedrockInsightAdvisor, not MockInsightAdvisor")
                .isInstanceOf(BedrockInsightAdvisor.class);

        AnalysisResult result = insightAdvisor.analyze(portfolio);

        assertThat(result).isNotNull();
        assertThat(result.riskScore())
                .as("Haiku 4.5 must return a risk score in the clamped 1–100 range")
                .isBetween(1, 100);
        assertThat(result.concentrationWarnings()).isNotNull();
        assertThat(result.rebalancingSuggestions())
                .as("system prompt caps suggestions at 3")
                .hasSizeLessThanOrEqualTo(3);
    }
}
