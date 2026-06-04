package com.wealth.insight;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 3: Preservation — plain-symbol resolution and response text unchanged after refactor.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4</b>
 *
 * <p>Tests {@link ChatResolutionService} + {@link ChatResponseBuilder} together (service chain),
 * exercising the same behavioral contracts previously tested through the HTTP controller layer.
 * Mocks are created per iteration so each jqwik property run is fully isolated.
 */
class ChatControllerPreservationPropertyTest {

    /** Fragment present in "Here's what I found for NAME (TICKER): ..." */
    private static final String OK_FRAGMENT         = "Here's what I found for";
    /** Fragment present in clarification responses (empty candidates). */
    private static final String CLARIFICATION_FRAG  = "couldn't identify a specific asset";
    /** Fragment present in no-data responses. */
    private static final String NO_DATA_FRAG        = "don't have any live data for";

    private static final CompactCatalog MINIMAL = new CompactCatalog(List.of(
            new CatalogEntry("AAPL", "Apple", List.of("Apple"), "US_EQUITY", "USD")
    ), "p3ver");

    // ── req 3.1 — catalog-known tracked symbol resolves and returns its summary ─────────────

    /**
     * P3.1: For any generated symbol S in the mocked catalog with Redis data:
     * "How is S doing?" → response contains "Here's what I found for ... (S)" and the price.
     */
    @Property(tries = 100)
    void p3_1_catalogKnownTrackedSymbol_returnsOkSummary(@ForAll("plainSymbols") String symbol) {
        TickerCatalogService catalog = mockCatalogWith(symbol);
        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);

        TickerSummary summary = new TickerSummary(
                symbol, new BigDecimal("123.45"), List.of(new BigDecimal("123.45")), null, null);
        when(mds.getTickerSummary(symbol)).thenReturn(summary);
        when(ai.getSentiment(anyString())).thenReturn("");

        String response = resolve(catalog, mds, ai, "How is " + symbol + " doing?");

        assertThat(response).contains(OK_FRAGMENT);
        assertThat(response).contains(symbol);
        assertThat(response).contains("123.45");
    }

    // ── req 3.1 — $-prefixed tracked symbol resolves ─────────────────────────────────────────

    /**
     * P3.1 ($-variant): "$S outlook" resolves via the dollar-pattern path.
     */
    @Property(tries = 100)
    void p3_1_dollarPrefixedCatalogSymbol_returnsOkSummary(@ForAll("plainSymbols") String symbol) {
        TickerCatalogService catalog = mockCatalogWith(symbol);
        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);

        TickerSummary summary = new TickerSummary(
                symbol, new BigDecimal("420.00"), List.of(new BigDecimal("420.00")), null, null);
        when(mds.getTickerSummary(symbol)).thenReturn(summary);
        when(ai.getSentiment(anyString())).thenReturn("");

        String response = resolve(catalog, mds, ai, "$" + symbol + " outlook");

        assertThat(response).contains(OK_FRAGMENT);
        assertThat(response).contains(symbol);
    }

    // ── req 3.2, 3.3 — messages with no catalog tokens return clarification ─────────────────

    /**
     * P3.2/3.3: Messages containing only common English words (not in catalog) → clarification.
     */
    @Property(tries = 100)
    void p3_2_noCatalogTokens_returnsClarification(@ForAll("noMatchMessages") String message) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.groundingView()).thenReturn(MINIMAL);

        String response = resolve(catalog, mock(MarketDataService.class), mock(AiInsightService.class), message);

        assertThat(response).contains(CLARIFICATION_FRAG);
    }

    // ── req 3.4 — catalog symbol with no Redis data returns no-data ──────────────────────────

    /**
     * P3.4: A catalog-known symbol with null latestPrice in Redis returns the no-data response.
     */
    @Property(tries = 100)
    void p3_4_catalogSymbolWithNoRedisData_returnsNoData(@ForAll("plainSymbols") String symbol) {
        TickerCatalogService catalog = mockCatalogWith(symbol);
        MarketDataService mds = mock(MarketDataService.class);
        // Returning null → no Redis data for this ticker
        when(mds.getTickerSummary(symbol)).thenReturn(null);

        String response = resolve(catalog, mds, mock(AiInsightService.class),
                "What about " + symbol + "?");

        assertThat(response)
                .as("P3.4: catalog symbol with no Redis data must produce no-data response for %s", symbol)
                .contains(NO_DATA_FRAG);
        assertThat(response).contains(symbol);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Creates a mock catalog where only {@code symbol} normalizes to itself. */
    private TickerCatalogService mockCatalogWith(String symbol) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize(symbol)).thenReturn(Optional.of(symbol));
        when(catalog.groundingView()).thenReturn(MINIMAL);
        CatalogEntry entry = new CatalogEntry(symbol, symbol, List.of(), "US_EQUITY", "USD");
        when(catalog.find(symbol)).thenReturn(Optional.of(entry));
        when(catalog.isSupported(symbol)).thenReturn(true);
        return catalog;
    }

    /** Calls {@code service.handle()} and returns the response text. */
    private String resolve(TickerCatalogService catalog, MarketDataService mds,
                           AiInsightService ai, String message) {
        ChatResponseBuilder builder = new ChatResponseBuilder(catalog, mds, ai);
        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), builder);
        ChatResponse resp = service.handle(new ChatRequest(message, null));
        return resp.response();
    }

    // ── Arbitraries ───────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> plainSymbols() {
        // 2–5 uppercase letters — arbitrary catalog-style symbols
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(2).ofMaxLength(5);
    }

    @Provide
    Arbitrary<String> noMatchMessages() {
        // Common English words that will never normalize to a catalog symbol
        return Arbitraries.of("how", "is", "the", "market", "doing", "today", "what", "about")
                .list().ofMinSize(1).ofMaxSize(5)
                .map(words -> String.join(" ", words));
    }
}
