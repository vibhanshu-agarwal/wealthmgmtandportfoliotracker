package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8.8 — Property 6: Risk-score invariant ({@code riskScore ∈ [1,100]}).
 *
 * <p>Generates arbitrary LLM risk scores and asserts both Azure and Bedrock advisors
 * clamp the returned {@link AnalysisResult} into the valid range.
 */
class RiskScoreClampingPropertyTest {

    @Property(tries = 100)
    void p6_azureAdvisor_clampsRiskScoreToValidRange(@ForAll("rawRiskScores") int rawScore) {
        AnalysisResult stubbed = new AnalysisResult(rawScore, List.of("warn"), List.of("suggest"));

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(stubbed);

        AzureOpenAiInsightAdvisor advisor = new AzureOpenAiInsightAdvisor(builder);
        AnalysisResult result = advisor.analyze(nonEmptyPortfolio());

        assertThat(result.riskScore()).isBetween(1, 100);
    }

    @Property(tries = 100)
    void p6_bedrockAdvisor_clampsRiskScoreToValidRange(@ForAll("rawRiskScores") int rawScore) {
        AnalysisResult stubbed = new AnalysisResult(rawScore, List.of("warn"), List.of("suggest"));

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(stubbed);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);
        AnalysisResult result = advisor.analyze(nonEmptyPortfolio());

        assertThat(result.riskScore()).isBetween(1, 100);
    }

    @Provide
    Arbitrary<Integer> rawRiskScores() {
        return Arbitraries.integers().between(-1000, 1000);
    }

    private static PortfolioDto nonEmptyPortfolio() {
        return new PortfolioDto(
                UUID.randomUUID(),
                "user-1",
                Instant.now(),
                List.of(new PortfolioHoldingDto(UUID.randomUUID(), "AAPL", new BigDecimal("10")))
        );
    }
}
