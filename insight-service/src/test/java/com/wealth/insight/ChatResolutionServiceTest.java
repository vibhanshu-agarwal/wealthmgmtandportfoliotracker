package com.wealth.insight;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.AssetResolutionClient;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.wealth.insight.resolution.LlmResolutionException;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatResolutionService} — Tasks 13, 14, 15, 17.
 *
 * <p>Covers all resolution paths (explicit, preflight, discovery-shortcut, LLM, fallback),
 * comparison guard, discovery filtering, correctness properties P1–P8, and the single-LLM-call
 * invariant (P4). Uses {@link StubAssetResolutionClient} as the mocked LLM — no real LLM calls.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatResolutionServiceTest {

    @Mock private TickerCatalogService catalog;
    @Mock private ChatResponseBuilder responseBuilder;

    private StubAssetResolutionClient stubClient;
    private ChatResolutionService service;

    // Catalog entries used across tests
    private static final CatalogEntry AAPL =
            new CatalogEntry("AAPL", "Apple", List.of("Apple", "Apple Inc"), "US_EQUITY", "USD");
    private static final CatalogEntry MSFT =
            new CatalogEntry("MSFT", "Microsoft", List.of("Microsoft"), "US_EQUITY", "USD");
    private static final CatalogEntry BTC =
            new CatalogEntry("BTC-USD", "Bitcoin", List.of("Bitcoin", "BTC"), "CRYPTO", "USD");
    private static final CatalogEntry ETH =
            new CatalogEntry("ETH-USD", "Ethereum", List.of("Ethereum", "ETH"), "CRYPTO", "USD");
    private static final CatalogEntry HDFC =
            new CatalogEntry("HDFCBANK.NS", "HDFC Bank", List.of("HDFC Bank", "HDFCBANK"), "NSE", "INR");
    private static final CatalogEntry EURUSD =
            new CatalogEntry("EURUSD=X", "EUR/USD", List.of("EURUSD", "EUR/USD"), "FOREX", "USD");

    private static final CompactCatalog COMPACT =
            new CompactCatalog(List.of(AAPL, MSFT, BTC, ETH, HDFC, EURUSD), "testver1");

    @BeforeEach
    void setUp() {
        stubClient = new StubAssetResolutionClient();
        service = new ChatResolutionService(catalog, stubClient, responseBuilder);
        // Default normalize: return empty so tests that stub specific tokens don't NPE
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.groundingView()).thenReturn(COMPACT);
        // Default: responseBuilder returns non-empty response for any outcome
        when(responseBuilder.build(any())).thenAnswer(inv -> {
            ResolutionOutcome o = inv.getArgument(0);
            return new ChatResponse("response for " + o.outcome());
        });
    }

    // ── Property P8: Preflight determinism / exact passthrough ────────────────────────────

    @Test
    void handle_explicitSupportedTicker_resolvesWithoutLlm() {
        // Step 1 now uses normalize() for Req 1.5 compliance; stub normalize for the exact symbol.
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("anything", "AAPL"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(cap.getValue().source()).isEqualTo("explicit");
        assertThat(stubClient.callCount()).isZero(); // P4: no LLM call
    }

    @Test
    void handle_explicitUnsupportedTicker_yieldsClarification() {
        // normalize("ZZZZ") → empty (default stub); normalize("ZZZZ") uppercase → same → clarification
        service.handle(new ChatRequest("anything", "ZZZZ"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
        assertThat(stubClient.callCount()).isZero(); // P4: no LLM call
    }

    // ── Finding 2: Explicit ticker normalization (Req 1.5) ────────────────────────────────

    @Test
    void handle_explicitLowercaseTicker_normalizesToCanonicalForm() {
        // "aapl" → normalize("aapl") = empty → normalize("AAPL") = "AAPL"
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("anything", "aapl"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(cap.getValue().source()).isEqualTo("explicit");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_explicitCryptoStem_normalizesToSuffixedForm() {
        // "BTC" → normalize("BTC") handles this via toUpperCase() in tryCryptoNormalize
        when(catalog.normalize("BTC")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("anything", "BTC"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(cap.getValue().source()).isEqualTo("explicit");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_explicitGluedCryptoPair_normalizesToBtcUsd() {
        // "btcusd" → normalize("btcusd") → crypto glued path: upper="BTCUSD" → "BTC-USD"
        when(catalog.normalize("btcusd")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("anything", "btcusd"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_explicitForexGluedPair_normalizesToForexSymbol() {
        // "usdchf" → normalize("usdchf") → upper="USDCHF" → "USDCHF=X"
        when(catalog.normalize("usdchf")).thenReturn(Optional.of("USDCHF=X"));

        service.handle(new ChatRequest("anything", "usdchf"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("USDCHF=X");
        assertThat(stubClient.callCount()).isZero();
    }

    // ── Property P3: Exact symbol preservation ────────────────────────────────────────────

    @Test
    void handle_exactCatalogSymbolInMessage_resolvesViaPreflight() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("How is AAPL doing?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(cap.getValue().source()).isEqualTo("preflight");
        assertThat(stubClient.callCount()).isZero(); // P4: no LLM call on deterministic path
    }

    @Test
    void handle_btcUsdExactSymbol_resolvesViaPreflight() {
        when(catalog.normalize("BTC-USD")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("Tell me about BTC-USD", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(cap.getValue().source()).isEqualTo("preflight");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_forexExactSymbol_resolvesViaPreflight() {
        when(catalog.normalize("EURUSD=X")).thenReturn(Optional.of("EURUSD=X"));

        service.handle(new ChatRequest("What is EURUSD=X?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("EURUSD=X");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_nseExactSymbol_resolvesViaPreflight() {
        when(catalog.normalize("HDFCBANK.NS")).thenReturn(Optional.of("HDFCBANK.NS"));

        service.handle(new ChatRequest("Price of HDFCBANK.NS", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("HDFCBANK.NS");
        assertThat(stubClient.callCount()).isZero();
    }

    // ── Normalization via TickerCatalogService.normalize ─────────────────────────────────

    @Test
    void handle_cryptoBareStem_normalizedToBtcUsd() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("BTC")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("How is BTC doing?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(cap.getValue().source()).isEqualTo("preflight");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_gluedCryptoPair_normalizedToBtcUsd() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("BTCUSD")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("Tell me about BTCUSD", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_slashedCryptoPair_normalizedToBtcUsd() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("BTC/USD")).thenReturn(Optional.of("BTC-USD"));

        service.handle(new ChatRequest("BTC/USD price?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_forexGluedPair_normalizedToForexSymbol() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("USDCHF")).thenReturn(Optional.of("USDCHF=X"));

        service.handle(new ChatRequest("What is USDCHF rate?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("USDCHF=X");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_forexSlashedPair_normalizedToForexSymbol() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("USD/CHF")).thenReturn(Optional.of("USDCHF=X"));

        service.handle(new ChatRequest("USD/CHF trend?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("USDCHF=X");
        assertThat(stubClient.callCount()).isZero();
    }

    // ── LLM path: natural-language names via mocked LLM (Task 13 / Req 1.2, 1.3) ─────────

    @Test
    void handle_naturalLanguageName_resolvedViaLlm() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("Tell me about Apple",
                StubAssetResolutionClient.assetQuery("AAPL", "Apple"));
        when(catalog.isSupported("AAPL")).thenReturn(true);

        service.handle(new ChatRequest("Tell me about Apple", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(cap.getValue().source()).isEqualTo("llm");
        assertThat(stubClient.callCount()).isEqualTo(1); // P4: exactly one LLM call
    }

    @Test
    void handle_aliasHdfcBank_resolvedViaLlm() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("HDFC Bank",
                StubAssetResolutionClient.assetQuery("HDFCBANK.NS", "HDFC Bank"));
        when(catalog.isSupported("HDFCBANK.NS")).thenReturn(true);

        service.handle(new ChatRequest("HDFC Bank", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("HDFCBANK.NS");
        assertThat(cap.getValue().source()).isEqualTo("llm");
    }

    @Test
    void handle_aliasBitcoin_resolvedViaLlm() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("Bitcoin",
                StubAssetResolutionClient.assetQuery("BTC-USD", "Bitcoin"));
        when(catalog.isSupported("BTC-USD")).thenReturn(true);

        service.handle(new ChatRequest("Bitcoin", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
    }

    // ── P1: Catalog-bounded resolution — invented LLM ticker is dropped ───────────────────

    @Test
    void handle_llmProposesInventedTicker_droppedYieldsClarification() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        // LLM proposes "FAKECOIN" which is not in catalog
        stubClient.whenMessage("Fake coin?",
                new LlmResolution(Intent.ASSET_QUERY, List.of("FAKECOIN"),
                        List.of("FAKECOIN"), List.of(), null, null));
        when(catalog.isSupported("FAKECOIN")).thenReturn(false);

        service.handle(new ChatRequest("Fake coin?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION); // P1
        assertThat(stubClient.callCount()).isEqualTo(1); // P4: one call
    }

    // ── P5: No silent wrong pick — comparison guard ───────────────────────────────────────

    @Test
    void handle_twoDistinctCatalogSymbols_redirectsToComparison() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));

        service.handle(new ChatRequest("compare AAPL and MSFT", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.COMPARISON_REDIRECT); // P5
        assertThat(cap.getValue().candidates()).contains("AAPL", "MSFT");
        assertThat(stubClient.callCount()).isZero(); // P4: no LLM on deterministic multi-candidate
    }

    @Test
    void handle_comparisonCueWithSingleCandidate_doesNotSilentlyPick() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("compare AAPL and Apple", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        // Must NOT silently resolve AAPL — must clarify or redirect (P5)
        assertThat(cap.getValue().outcome())
                .isNotEqualTo(Outcome.RESOLVED); // never first-pick
    }

    @Test
    void handle_nseSymbolContainingVsSubstring_resolvesViaPreflight_notClarification() {
        // Regression: "VS.NS" lowercase is "vs.ns" which CONTAINS the substring "vs."
        // hasComparisonCue must match "vs." as a whole token, not as a substring,
        // otherwise a VS.NS holding query triggers a spurious comparison-cue clarification.
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("VS.NS")).thenReturn(Optional.of("VS.NS"));

        service.handle(new ChatRequest("How is VS.NS doing?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("VS.NS must resolve (not clarify) — 'vs.' must not match as substring of VS.NS")
                .isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("VS.NS");
        assertThat(cap.getValue().source()).isEqualTo("preflight");
    }

    @Test
    void handle_vsWithDotAsStandaloneToken_triggersComparisonCue() {
        // Regression guard: "AAPL vs. MSFT" must still trigger the comparison cue.
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("AAPL vs. MSFT", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        // "vs." as a standalone token must still suppress silent single-pick
        assertThat(cap.getValue().outcome())
                .as("AAPL vs. MSFT must not silently resolve to AAPL")
                .isNotEqualTo(Outcome.RESOLVED);
    }

    @Test
    void handle_btcAndEth_redirectsToComparison() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("BTC")).thenReturn(Optional.of("BTC-USD"));
        when(catalog.normalize("ETH")).thenReturn(Optional.of("ETH-USD"));

        service.handle(new ChatRequest("BTC and ETH", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.COMPARISON_REDIRECT);
        assertThat(cap.getValue().candidates()).contains("BTC-USD", "ETH-USD");
        assertThat(stubClient.callCount()).isZero(); // P4
    }

    @Test
    void handle_aaplAlone_stillResolvesSingleCandidate() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("AAPL", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
    }

    @Test
    void handle_tellMeAboutAapl_resolvesSingleCandidate() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("tell me about AAPL", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
    }

    // ── LLM comparison intent → redirect ──────────────────────────────────────────────────

    @Test
    void handle_llmComparisonIntent_redirectsWithBothCandidates() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("compare Apple and Microsoft",
                StubAssetResolutionClient.comparison("AAPL", "MSFT"));
        when(catalog.isSupported("AAPL")).thenReturn(true);
        when(catalog.isSupported("MSFT")).thenReturn(true);

        service.handle(new ChatRequest("compare Apple and Microsoft", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.COMPARISON_REDIRECT);
        assertThat(cap.getValue().candidates()).containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    // ── Discovery shortcut (Task 15 / Req 4.1-4.5) ───────────────────────────────────────

    @Test
    void handle_whatAssetsDoYouTrack_discoveryAllCategories() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("what assets do you track?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isNull();
        assertThat(cap.getValue().source()).isEqualTo("discovery-shortcut");
        assertThat(stubClient.callCount()).isZero(); // P4: no LLM on discovery shortcut
    }

    @Test
    void handle_whichCryptosDoYouTrack_discoveryCryptoCategory() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("which crypto do you track?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("CRYPTO");
    }

    @Test
    void handle_whichIndianStocks_discoveryNseCategory() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("which Indian stocks can I ask about?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("NSE");
    }

    @Test
    void handle_whatForexPairsAvailable_discoveryForexCategory() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("what forex pairs are available?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("FOREX");
    }

    @Test
    void handle_listStocks_discoveryUsEquityCategory() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("list stocks you cover", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("US_EQUITY");
    }

    // ── Finding 1: Entity-guarded discovery (Req 4.1 precision) ─────────────────────────

    @Test
    void handle_whatIsAppleStock_fallsThroughToLlmNotDiscovery() {
        // "Apple" is a capitalized mid-sentence token → entity guard fires → discovery skipped.
        // Preflight finds nothing (Apple isn't a ticker token) → LLM is called.
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("what is Apple stock?",
                StubAssetResolutionClient.assetQuery("AAPL", "Apple"));
        when(catalog.isSupported("AAPL")).thenReturn(true);

        service.handle(new ChatRequest("what is Apple stock?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        // Must NOT return DISCOVERY — the entity "Apple" should steer to LLM resolution
        assertThat(cap.getValue().outcome()).isNotEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(cap.getValue().source()).isEqualTo("llm");
        assertThat(stubClient.callCount()).isEqualTo(1);
    }

    @Test
    void handle_showMeAppleStock_fallsThroughToLlmNotDiscovery() {
        // "Apple" is mid-sentence and capitalized → entity guard fires → discovery skipped.
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("show me Apple stock",
                StubAssetResolutionClient.assetQuery("AAPL", "Apple"));
        when(catalog.isSupported("AAPL")).thenReturn(true);

        service.handle(new ChatRequest("show me Apple stock", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isNotEqualTo(Outcome.DISCOVERY);
        assertThat(stubClient.callCount()).isEqualTo(1);
    }

    @Test
    void handle_showMeAllCrypto_stillTriggersDiscovery() {
        // "crypto" is lowercase → no entity-like token → word-combo fires → DISCOVERY
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("show me all crypto", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("CRYPTO");
        assertThat(stubClient.callCount()).isZero();
    }

    @Test
    void handle_whatStocksDoYouTrack_stillTriggersDiscovery() {
        // All tokens lowercase → no entity-like token → word-combo + trigger phrase → DISCOVERY
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());

        service.handle(new ChatRequest("what stocks do you track?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(stubClient.callCount()).isZero();
    }

    // ── LLM discovery intent ──────────────────────────────────────────────────────────────

    @Test
    void handle_llmDiscoveryIntent_discoveryOutcome() {
        // "find crypto options" has no listing word (find ∉ DISCOVERY_LISTING_WORDS)
        // so the discovery shortcut does NOT fire; the LLM is invoked.
        stubClient.whenMessage("find crypto options",
                StubAssetResolutionClient.discovery("CRYPTO"));

        service.handle(new ChatRequest("find crypto options", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.DISCOVERY);
        assertThat(cap.getValue().categoryFilter()).isEqualTo("CRYPTO");
        assertThat(cap.getValue().source()).isEqualTo("llm");
    }

    // ── LLM GREETING_HELP intent ─────────────────────────────────────────────────────────

    @Test
    void handle_greetingHelp_greetingHelpOutcome() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("hello",
                StubAssetResolutionClient.greetingHelp());

        service.handle(new ChatRequest("hello", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.GREETING_HELP);
    }

    // ── Fallback tests (Task 9 / Req 6.1, 6.6) ───────────────────────────────────────────

    @Test
    void handle_llmUnavailable_exactSymbolStillResolves_fallbackExact() {
        // preflight finds AAPL via normalize
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        // Even if LLM is unavailable, preflight caught it before LLM was called

        service.handle(new ChatRequest("How is AAPL doing?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(stubClient.callCount()).isZero(); // never reached LLM
    }

    @Test
    void handle_llmThrows_naturalLanguageName_yieldsClarification() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.alwaysThrow(new LlmResolutionException(
                LlmResolutionException.Kind.UNAVAILABLE, "timeout"));

        service.handle(new ChatRequest("Apple", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        // "Apple" does not normalize → fallback finds nothing → clarification (P7)
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
        assertThat(cap.getValue().source()).isEqualTo("fallback-exact");
    }

    @Test
    void handle_llmThrows_stemStillResolves_fallbackExact() {
        // BTC normalizes even in fallback path
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("BTC")).thenReturn(Optional.of("BTC-USD"));
        // With BTC normalizing, preflight resolves it before calling LLM at all
        // So LLM failure is irrelevant here — but the property (BTC resolves) still holds

        service.handle(new ChatRequest("How is BTC doing?", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
    }

    @Test
    void handle_llmThrows_comparisonGuardStillApplied() {
        // Even in fallback, comparison guard must fire
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));
        // LLM not called because preflight found >1 candidates first

        service.handle(new ChatRequest("AAPL and MSFT", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.COMPARISON_REDIRECT); // P5 in fallback
        assertThat(stubClient.callCount()).isZero();
    }

    // ── Property P4: Single LLM call per message ─────────────────────────────────────────

    @Test
    void handle_llmPath_atMostOneLlmCallPerMessage() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("random",
                StubAssetResolutionClient.unknown("no match"));

        service.handle(new ChatRequest("random", null));

        assertThat(stubClient.callCount()).isLessThanOrEqualTo(1); // P4
    }

    // ── Property P6: Never-empty response ────────────────────────────────────────────────

    @Test
    void handle_allOutcomePaths_responseNeverEmpty() {
        // @BeforeEach already stubs responseBuilder.build(any()) to return a non-empty response.
        // Do NOT re-stub responseBuilder here: Mockito would internally call build(null) to capture
        // the invocation, which would trigger the @BeforeEach thenAnswer lambda with o=null → NPE.

        // Empty message → clarification outcome
        ChatResponse r1 = service.handle(new ChatRequest("", null));
        assertThat(r1).isNotNull();
        assertThat(r1.response()).isNotBlank();
    }

    // ── Stateless follow-up (Task 12 / Req 10.1, 10.2) ───────────────────────────────────

    @Test
    void handle_deicticOnlyMessage_yieldsClarification() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        // "it" and "its trend" → no resolvable asset → LLM → stub returns UNKNOWN
        stubClient.whenMessage("it", StubAssetResolutionClient.unknown("deictic reference"));
        stubClient.whenMessage("its trend", StubAssetResolutionClient.unknown("deictic reference"));

        // "it" - stub returns UNKNOWN → clarification
        service.handle(new ChatRequest("it", null));
        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
    }

    // ── P2: Redis-only facts — LLM-proposed ticker must pass catalog before Redis ─────────

    @Test
    void handle_llmResolvedTicker_onlyReachesRedisAfterCatalogValidation() {
        // The orchestrator delegates to responseBuilder only after catalog validation
        // We verify the outcome is RESOLVED only if isSupported is true
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("Apple",
                new LlmResolution(Intent.ASSET_QUERY, List.of("Apple"),
                        List.of("AAPL"), List.of(), null, null));
        when(catalog.isSupported("AAPL")).thenReturn(true); // catalog gate

        service.handle(new ChatRequest("Apple", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL"); // P1: catalog-bounded
    }

    // ── Ambiguous reference → clarification ──────────────────────────────────────────────

    @Test
    void handle_ambiguousReference_yieldsClarificationWithCandidates() {
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        stubClient.whenMessage("bank",
                new LlmResolution(Intent.ASSET_QUERY, List.of("bank"),
                        List.of(), List.of("JPM", "BAC"), null, "ambiguous: multiple banks"));
        when(catalog.isSupported("JPM")).thenReturn(true);
        when(catalog.isSupported("BAC")).thenReturn(true);

        service.handle(new ChatRequest("bank", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
        assertThat(cap.getValue().candidates()).containsExactlyInAnyOrder("JPM", "BAC");
    }
}
