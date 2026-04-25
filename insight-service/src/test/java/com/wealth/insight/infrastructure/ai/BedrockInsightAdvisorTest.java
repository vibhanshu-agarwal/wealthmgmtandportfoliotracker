package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BedrockInsightAdvisor} that mock the Spring AI {@link ChatClient}
 * fluent chain. Exercises the real Bedrock code path without calling AWS — covers the
 * empty-portfolio short-circuit, happy-path entity mapping, risk-score clamping, and
 * exception wrapping.
 *
 * <p>Pair with {@link BedrockLiveSmokeTest} for end-to-end validation against AWS.
 */
class BedrockInsightAdvisorTest {

    private static final UUID PORTFOLIO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void analyze_emptyPortfolio_shortCircuitsWithoutCallingBedrock() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        PortfolioDto empty = new PortfolioDto(PORTFOLIO_ID, "user-1", Instant.now(), List.of());

        AnalysisResult result = advisor.analyze(empty);

        assertThat(result.riskScore()).isZero();
        assertThat(result.concentrationWarnings()).isEmpty();
        assertThat(result.rebalancingSuggestions()).isEmpty();
        // No prompt, no call — the ChatClient chain is never traversed past prompt()
        verify(builder.defaultSystem(anyString()).build(), never()).prompt();
    }

    @Test
    void analyze_happyPath_returnsEntityResult() {
        AnalysisResult stubbed = new AnalysisResult(
                42,
                List.of("AAPL > 60% of portfolio"),
                List.of("Diversify into bonds", "Add international equities")
        );

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(stubbed);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        PortfolioDto portfolio = new PortfolioDto(PORTFOLIO_ID, "user-1", Instant.now(), List.of(
                new PortfolioHoldingDto(UUID.randomUUID(), "AAPL", new BigDecimal("10"))
        ));

        AnalysisResult result = advisor.analyze(portfolio);

        assertThat(result.riskScore()).isEqualTo(42);
        assertThat(result.concentrationWarnings()).containsExactly("AAPL > 60% of portfolio");
        assertThat(result.rebalancingSuggestions()).hasSize(2);
    }

    @Test
    void analyze_riskScoreAbove100_clampedTo100() {
        AnalysisResult outOfRange = new AnalysisResult(150, List.of(), List.of());

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(outOfRange);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        PortfolioDto portfolio = singleHoldingPortfolio();

        AnalysisResult result = advisor.analyze(portfolio);

        assertThat(result.riskScore()).isEqualTo(100);
    }

    @Test
    void analyze_riskScoreBelow1_clampedTo1() {
        AnalysisResult outOfRange = new AnalysisResult(-5, List.of(), List.of());

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(outOfRange);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        AnalysisResult result = advisor.analyze(singleHoldingPortfolio());

        assertThat(result.riskScore()).isEqualTo(1);
    }

    @Test
    void analyze_nullEntity_throwsAdvisorUnavailable() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenReturn(null);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        assertThatThrownBy(() -> advisor.analyze(singleHoldingPortfolio()))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessageContaining("null response");
    }

    @Test
    void analyze_chatClientThrows_wrappedAsAdvisorUnavailable() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenThrow(new RuntimeException("Bedrock throttled"));

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        assertThatThrownBy(() -> advisor.analyze(singleHoldingPortfolio()))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessageContaining("Bedrock advisor unavailable")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void analyze_advisorUnavailable_propagatedWithoutRewrapping() {
        AdvisorUnavailableException inner = new AdvisorUnavailableException("upstream failure");

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.defaultSystem(anyString()).build().prompt().user(anyString()).call().entity(AnalysisResult.class))
                .thenThrow(inner);

        BedrockInsightAdvisor advisor = new BedrockInsightAdvisor(builder);

        assertThatThrownBy(() -> advisor.analyze(singleHoldingPortfolio()))
                .isSameAs(inner);
    }

    private static PortfolioDto singleHoldingPortfolio() {
        return new PortfolioDto(PORTFOLIO_ID, "user-1", Instant.now(), List.of(
                new PortfolioHoldingDto(UUID.randomUUID(), "AAPL", new BigDecimal("10"))
        ));
    }
}
