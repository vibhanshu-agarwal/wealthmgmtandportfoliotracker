package com.wealth.insight;

import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link ChatResolutionService#extractNormalizedCandidates(String)}
 * (supersedes legacy {@code ChatController.extractTicker} property tests — Task 10 refactor).
 *
 * <p>Property 7: A catalog-known ticker token placed in a message is always extracted.
 * <p>Property 8: Multiple catalog-known tickers are all extracted.
 * <p>Property 9: Messages with no catalog-known tokens produce empty candidates.
 */
class TickerExtractionPropertyTest {

    /**
     * P7: For any catalog-known symbol placed as a token in a message,
     * {@code extractNormalizedCandidates} returns it.
     */
    @Property(tries = 100)
    void p7_catalogKnownTicker_inMessage_isExtracted(@ForAll("catalogSymbols") String symbol) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize(symbol)).thenReturn(Optional.of(symbol));

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), mock(ChatResponseBuilder.class));

        assertThat(service.extractNormalizedCandidates("How is " + symbol + " doing?"))
                .containsExactly(symbol);
    }

    /**
     * P8: For any two distinct catalog-known symbols in a message,
     * both are returned as candidates.
     */
    @Property(tries = 100)
    void p8_twoCatalogTickers_bothExtracted(@ForAll("twoDistinctSymbols") List<String> symbols) {
        String a = symbols.get(0);
        String b = symbols.get(1);

        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize(a)).thenReturn(Optional.of(a));
        when(catalog.normalize(b)).thenReturn(Optional.of(b));

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), mock(ChatResponseBuilder.class));

        assertThat(service.extractNormalizedCandidates("Compare " + a + " and " + b))
                .containsExactlyInAnyOrder(a, b);
    }

    /**
     * P9: For a message composed only of common English words not in the catalog,
     * {@code extractNormalizedCandidates} returns an empty list.
     */
    @Property(tries = 100)
    void p9_noCatalogTokens_returnsEmpty(@ForAll("nonCatalogMessages") String message) {
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), mock(ChatResponseBuilder.class));

        assertThat(service.extractNormalizedCandidates(message)).isEmpty();
    }

    // ── Arbitraries ───────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> catalogSymbols() {
        // 2–5 uppercase letters — arbitrary catalog symbols
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(2).ofMaxLength(5);
    }

    @Provide
    Arbitrary<List<String>> twoDistinctSymbols() {
        return catalogSymbols().set().ofSize(2).map(Set::stream).map(s -> s.toList());
    }

    @Provide
    Arbitrary<String> nonCatalogMessages() {
        // Messages made of common English words — none will normalize to a catalog symbol
        return Arbitraries.of("how", "is", "the", "market", "doing", "what", "are", "today")
                .list().ofMinSize(1).ofMaxSize(6)
                .map(words -> String.join(" ", words));
    }
}
