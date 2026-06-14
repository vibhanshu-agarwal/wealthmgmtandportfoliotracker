package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.ChatResolutionService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.ResolutionOutcome;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8.7 — Property 4: AI behavior parity under the mock profile
 * ({@code spring.ai.model.chat=none}).
 *
 * <p>Verifies mock adapters are wired and core chat paths ({@link ChatResolutionService},
 * {@link ChatResponseBuilder}) behave as before when no live ChatModel is present.
 */
class MockProfileAiParityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AiConfig.class,
                    MockAiInsightService.class,
                    MockInsightAdvisor.class,
                    MockAssetResolutionClient.class
            )
            .withPropertyValues(
                    "spring.ai.model.chat=none",
                    "spring.profiles.active=local"
            );

    @Test
    void mockProfile_noChatModelBean_mockAdaptersWired() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ChatModel.class);
            assertThat(ctx.getBean(AiInsightService.class)).isInstanceOf(MockAiInsightService.class);
            assertThat(ctx.getBean(InsightAdvisor.class)).isInstanceOf(MockInsightAdvisor.class);
            assertThat(ctx.getBean(MockAiInsightService.class).getSentiment("AAPL"))
                    .contains("AAPL")
                    .contains("Neutral");
        });
    }

    @Test
    void chatResponseBuilder_advisorUnavailable_appendsDegradationNote() {
        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary("AAPL")).thenReturn(new TickerSummary(
                "AAPL",
                new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50")),
                null,
                null
        ));
        when(marketData.getMarketSummary()).thenReturn(Map.of());

        AiInsightService aiInsight = mock(AiInsightService.class);
        when(aiInsight.getSentiment("AAPL"))
                .thenThrow(new AdvisorUnavailableException("AI down"));

        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.find("AAPL")).thenReturn(java.util.Optional.of(
                new com.wealth.insight.catalog.CatalogEntry(
                        "AAPL", "Apple", List.of("Apple"), "US_EQUITY", "USD")));

        ChatResponseBuilder builder = new ChatResponseBuilder(catalog, marketData, aiInsight);
        ChatResponse response = builder.build(ResolutionOutcome.resolved("AAPL", "preflight"));

        assertThat(response.response()).contains("178.50");
        assertThat(response.response().toLowerCase())
                .containsAnyOf("ai analysis", "temporarily unavailable", "unavailable");
    }

    @Test
    void chatResolutionService_preflightPath_producesNonBlankResponse() {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize("AAPL")).thenReturn(java.util.Optional.of("AAPL"));
        when(catalog.isSupported("AAPL")).thenReturn(true);

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary("AAPL")).thenReturn(new TickerSummary(
                "AAPL",
                new BigDecimal("180.00"),
                List.of(new BigDecimal("180.00")),
                null,
                null
        ));
        when(marketData.getMarketSummary()).thenReturn(Map.of());

        ChatResponseBuilder responseBuilder = new ChatResponseBuilder(
                catalog, marketData, new MockAiInsightService());

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), responseBuilder);

        ChatResponse response = service.handle(new ChatRequest("AAPL", null));

        assertThat(response).isNotNull();
        assertThat(response.response()).isNotBlank();
    }
}
