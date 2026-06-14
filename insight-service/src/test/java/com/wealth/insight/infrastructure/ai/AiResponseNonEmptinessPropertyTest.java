package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.ChatResolutionService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.wealth.insight.resolution.LlmResolutionException;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8.9 — Property 4: AI behavior parity (non-blank {@link ChatResponse}).
 *
 * <p>Generates arbitrary user messages and resolution paths; every outcome must yield a
 * non-null, non-blank assistant response under the mock-profile stack.
 */
class AiResponseNonEmptinessPropertyTest {

    private static final CatalogEntry AAPL =
            new CatalogEntry("AAPL", "Apple", List.of("Apple"), "US_EQUITY", "USD");
    private static final CompactCatalog COMPACT =
            new CompactCatalog(List.of(AAPL), "p4ver");

    @Property(tries = 100)
    void p4_anyUserMessage_responseIsNeverBlank(@ForAll("userMessages") String message) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.groundingView()).thenReturn(COMPACT);
        when(catalog.catalogVersion()).thenReturn("p4ver");

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getMarketSummary()).thenReturn(Map.of());

        ChatResponseBuilder responseBuilder = new ChatResponseBuilder(
                catalog, marketData, new MockAiInsightService());

        StubAssetResolutionClient stub = new StubAssetResolutionClient();
        stub.whenMessage(message,
                new LlmResolution(Intent.UNKNOWN, List.of(), List.of(), List.of(), null, null));

        ChatResolutionService service = new ChatResolutionService(catalog, stub, responseBuilder);

        ChatResponse response = service.handle(new ChatRequest(message, null));

        assertThat(response).isNotNull();
        assertThat(response.response()).isNotBlank();
    }

    @Property(tries = 50)
    void p4_llmFailurePath_responseIsNeverBlank(@ForAll("userMessages") String message) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.groundingView()).thenReturn(COMPACT);
        when(catalog.catalogVersion()).thenReturn("p4ver");

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getMarketSummary()).thenReturn(Map.of());

        ChatResponseBuilder responseBuilder = new ChatResponseBuilder(
                catalog, marketData, new MockAiInsightService());

        StubAssetResolutionClient stub = new StubAssetResolutionClient();
        stub.alwaysThrow(new LlmResolutionException(LlmResolutionException.Kind.UNAVAILABLE, "down"));

        ChatResolutionService service = new ChatResolutionService(catalog, stub, responseBuilder);

        ChatResponse response = service.handle(new ChatRequest(message, null));

        assertThat(response).isNotNull();
        assertThat(response.response()).isNotBlank();
    }

    @Property(tries = 50)
    void p4_resolvedTickerPath_responseIsNeverBlank(@ForAll("userMessages") String message) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.isSupported("AAPL")).thenReturn(true);
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.groundingView()).thenReturn(COMPACT);

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary("AAPL")).thenReturn(new TickerSummary(
                "AAPL", new BigDecimal("180.00"), List.of(new BigDecimal("180.00")), null, null));
        when(marketData.getMarketSummary()).thenReturn(Map.of());

        ChatResponseBuilder responseBuilder = new ChatResponseBuilder(
                catalog, marketData, new MockAiInsightService());

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), responseBuilder);

        ChatResponse response = service.handle(new ChatRequest("AAPL " + message, null));

        assertThat(response).isNotNull();
        assertThat(response.response()).isNotBlank();
    }

    @Provide
    Arbitrary<String> userMessages() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(80);
    }
}
