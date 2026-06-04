package com.wealth.insight;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property 2 — Suffixed catalog symbols resolve to themselves via preflight normalization.
 *
 * <p><b>Validates: Requirements 1.3, 1.4, 1.5, 1.6, 2.3, 2.4, 2.5, 2.6</b>
 *
 * <p>In the new catalog-first architecture, a suffixed symbol S (e.g. {@code ROSE-USD},
 * {@code USDCHF=X}, {@code RELIANCE.NS}) is resolvable when it is in the catalog. The
 * {@link TickerCatalogService#normalize(String)} performs exact passthrough for exact catalog
 * symbols, so {@code normalize("ROSE-USD") = "ROSE-USD"} — no separate suffix-priority ordering
 * needed.
 *
 * <h2>Property (P2 — catalog-based resolution)</h2>
 * <pre>
 * FOR ALL S in catalog where hasSuffixFormat(S):
 *   outcome := service.handle(new ChatRequest("How is " + S + " doing?", null))
 *   ASSERT outcome.ticker() = S         // exact catalog symbol, suffix intact
 *   ASSERT outcome.outcome() = RESOLVED // preflight resolves without LLM
 * </pre>
 */
class ChatControllerSuffixResolutionPropertyTest {

    private static final CompactCatalog MINIMAL_COMPACT = new CompactCatalog(List.of(
            new CatalogEntry("BTC-USD", "Bitcoin", List.of("BTC"), "CRYPTO", "USD")
    ), "prop2ver");

    /**
     * P2: For any catalog-known suffixed symbol S (suffix {@code -USD} / {@code =X} / {@code .NS}),
     * a chat message referencing S exactly as a token resolves to S via catalog preflight.
     *
     * <p>The LLM stub returns {@code UNKNOWN} by default, confirming the resolution is purely
     * deterministic (no LLM involved — preflight path only).
     */
    @Property(tries = 100)
    void p2_catalogSuffixedSymbol_resolvesToExactSymbolViaPreflight(
            @ForAll("suffixedSymbols") String symbol) {

        // Arrange: mock catalog so 'symbol' normalizes to itself (catalog-known)
        TickerCatalogService catalog = mock(TickerCatalogService.class);
        ChatResponseBuilder builder = mock(ChatResponseBuilder.class);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize(symbol)).thenReturn(Optional.of(symbol));
        when(catalog.groundingView()).thenReturn(MINIMAL_COMPACT);
        when(builder.build(any())).thenReturn(new ChatResponse("stub-response"));

        ChatResolutionService service = new ChatResolutionService(
                catalog, new StubAssetResolutionClient(), builder);

        // Act: message references the suffixed symbol as a whitespace-delimited token
        service.handle(new ChatRequest("How is " + symbol + " doing?", null));

        // Assert (P2): resolution is RESOLVED to the exact suffixed symbol via preflight
        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(builder).build(cap.capture());
        assertThat(cap.getValue().ticker())
                .as("P2: suffixed symbol %s must resolve to itself (suffix intact)", symbol)
                .isEqualTo(symbol);
        assertThat(cap.getValue().outcome())
                .as("P2: resolution must be RESOLVED (not CLARIFICATION or NO_DATA)")
                .isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().source())
                .as("P2: preflight path resolves without LLM")
                .isEqualTo("preflight");
    }

    /**
     * Generates suffixed symbols over the catalog alphabet {@code {-USD, =X, .NS}}.
     * Each stem is chosen from uppercase letters to match the catalog token format.
     */
    @Provide
    Arbitrary<String> suffixedSymbols() {
        Arbitrary<String> crypto = upperLetters(2, 5).map(stem -> stem + "-USD");
        Arbitrary<String> forex  = upperLetters(6, 6).map(stem -> stem + "=X");
        Arbitrary<String> nse    = upperLetters(2, 10).map(stem -> stem + ".NS");
        return Arbitraries.oneOf(crypto, forex, nse);
    }

    private static Arbitrary<String> upperLetters(int min, int max) {
        return Arbitraries.strings()
                .withChars('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
                        'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z')
                .ofMinLength(min).ofMaxLength(max);
    }
}
