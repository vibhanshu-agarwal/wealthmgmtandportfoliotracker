package com.wealth.insight;

import com.wealth.insight.AiInsightService;
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
import com.wealth.insight.resolution.LlmResolutionException.Kind;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Machine-checkable assertions for the eight design correctness properties (Task 17 /
 * design.md §Correctness Properties).
 *
 * <p>Each test method is named {@code pN_...} where N is the design property number.
 * Tests use only deterministic collaborators — no live LLM or Redis calls (Req 9.3).
 *
 * <ul>
 *   <li>P1 — Catalog-bounded: every resolved ticker must originate in the supported catalog.</li>
 *   <li>P2 — Redis-only facts: prices come exclusively from {@link MarketDataService} (Redis),
 *       never from the LLM; {@link LlmResolution} carries no price fields.</li>
 *   <li>P3 — Exact-symbol preservation: suffixed symbols ({@code BTC-USD}, {@code USDCHF=X},
 *       {@code RELIANCE.NS}) resolve via the deterministic preflight path.</li>
 *   <li>P4 — Single LLM call: at most one {@code AssetResolutionClient.resolve()} per turn.</li>
 *   <li>P5 — No silent wrong pick: ambiguous/multi-candidate messages yield
 *       {@link Outcome#CLARIFICATION} or {@link Outcome#COMPARISON_REDIRECT}, never a silent
 *       first-pick resolve.</li>
 *   <li>P6 — Never-empty: every outcome path produces a non-null, non-blank assistant message.</li>
 *   <li>P7 — Fallback safety: deterministic resolution takes over when the LLM fails;
 *       natural-language names yield clarification rather than a crash.</li>
 *   <li>P8 — Determinism: preflight and fallback paths produce identical outputs for identical
 *       inputs on every invocation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorrectnessPropertyTest {

    @Mock private TickerCatalogService catalog;
    @Mock private ChatResponseBuilder responseBuilder;
    @Mock private MarketDataService marketData;
    @Mock private AiInsightService aiInsight;

    private StubAssetResolutionClient stubClient;
    private ChatResolutionService service;

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
    private static final CatalogEntry RELIANCE =
            new CatalogEntry("RELIANCE.NS", "Reliance Industries", List.of("Reliance"), "NSE", "INR");
    private static final CatalogEntry USDCHF =
            new CatalogEntry("USDCHF=X", "USD/CHF", List.of("USDCHF", "USD/CHF"), "FOREX", "USD");

    private static final CompactCatalog COMPACT = new CompactCatalog(
            List.of(AAPL, MSFT, BTC, ETH, HDFC, RELIANCE, USDCHF), "test-cv1");

    @BeforeEach
    void setUp() {
        stubClient = new StubAssetResolutionClient();
        service = new ChatResolutionService(catalog, stubClient, responseBuilder);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
        when(catalog.groundingView()).thenReturn(COMPACT);
        when(catalog.catalogVersion()).thenReturn("test-cv1");
        when(responseBuilder.build(any())).thenAnswer(inv -> {
            ResolutionOutcome o = inv.getArgument(0);
            return new ChatResponse("response:" + o.outcome() + ":" + o.ticker());
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P1 — Catalog-bounded (Req 2.5 / design.md Property 1)
    // Every resolved ticker must be validated against the supported catalog before
    // being surfaced in the outcome. LLM-invented tickers are silently dropped.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p1_llmProposesSingleInventedTicker_droppedYieldsClarification() {
        stubClient.whenMessage("ACME stock",
                new LlmResolution(Intent.ASSET_QUERY, List.of("ACME"),
                        List.of("ACME-USD"), List.of(), null, null));
        when(catalog.isSupported("ACME-USD")).thenReturn(false);

        service.handle(new ChatRequest("ACME stock", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P1: invented ticker must be dropped — clarification required")
                .isEqualTo(Outcome.CLARIFICATION);
        assertThat(cap.getValue().ticker())
                .as("P1: no invented ticker should appear in the outcome")
                .isNull();
    }

    @Test
    void p1_llmProposesPartiallyValidTickers_onlyCatalogValidSurvives() {
        // resolvedTickers=[AAPL] (valid), candidateTickers=[FAKECOIN] (invalid)
        stubClient.whenMessage("Apple or Fakecoin",
                new LlmResolution(Intent.ASSET_QUERY, List.of("Apple", "Fakecoin"),
                        List.of("AAPL"), List.of("FAKECOIN"), null, null));
        when(catalog.isSupported("AAPL")).thenReturn(true);
        when(catalog.isSupported("FAKECOIN")).thenReturn(false);

        service.handle(new ChatRequest("Apple or Fakecoin", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker())
                .as("P1: AAPL passes gate; FAKECOIN is dropped")
                .isEqualTo("AAPL");
    }

    @Test
    void p1_explicitTickerNotInCatalog_yieldsClarification_noCatalogBypass() {
        // normalize("INVENTED-XYZ") and normalize("INVENTED-XYZ" uppercase) both return empty
        service.handle(new ChatRequest("price query", "INVENTED-XYZ"));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P1: unsupported explicit ticker must not resolve")
                .isEqualTo(Outcome.CLARIFICATION);
    }

    @Test
    void p1_llmProposesValidTicker_passesGate_resolves() {
        stubClient.whenMessage("Apple price",
                new LlmResolution(Intent.ASSET_QUERY, List.of("Apple"),
                        List.of("AAPL"), List.of(), null, null));
        when(catalog.isSupported("AAPL")).thenReturn(true);

        service.handle(new ChatRequest("Apple price", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P1: catalog-valid ticker passes gate and resolves")
                .isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P2 — Redis-only facts (Req 3.1, 3.2 / design.md Property 2)
    // Prices and trend data come exclusively from MarketDataService (Redis).
    // LlmResolution carries no price fields — the LLM is structurally unable to
    // inject numbers into the response.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p2_llmResolutionHasNoPriceFields_structuralInvariant() {
        // If this fails, a price field was added to LlmResolution — a P2 violation.
        var components = LlmResolution.class.getRecordComponents();
        var fieldNames = java.util.Arrays.stream(components)
                .map(rc -> rc.getName())
                .toList();
        assertThat(fieldNames)
                .as("P2: LlmResolution must contain no price/trend fields")
                .doesNotContainAnyElementsOf(
                        List.of("price", "latestPrice", "trend", "change",
                                "priceChange", "percentChange", "amount", "value"));
        assertThat(fieldNames)
                .as("P2: confirm the expected LlmResolution schema is unchanged")
                .containsExactlyInAnyOrder(
                        "intent", "entities", "resolvedTickers",
                        "candidateTickers", "categoryFilter", "clarificationReason");
    }

    @Test
    void p2_responseBuilderFetchesPriceFromRedis_notFromLlm() {
        // Build a real ChatResponseBuilder backed by mocked MarketDataService (simulated Redis).
        ChatResponseBuilder realBuilder = new ChatResponseBuilder(catalog, marketData, aiInsight);
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        TickerSummary redisSummary = new TickerSummary(
                "AAPL", new BigDecimal("195.75"), List.of(new BigDecimal("195.75")), null, null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(redisSummary);

        ChatResponse resp = realBuilder.build(ResolutionOutcome.resolved("AAPL", "llm"));

        assertThat(resp.response())
                .as("P2: price in response must come from Redis (TickerSummary.latestPrice)")
                .contains("195.75");
        verify(marketData).getTickerSummary("AAPL");
    }

    @Test
    void p2_nullLatestPriceFromRedis_triggersNoDataResponse_aiInsightNeverCalledForPrice() {
        // When Redis has no price, the system returns a no-data response.
        // The system must NOT use LLM-supplied numbers as a fallback price source.
        ChatResponseBuilder realBuilder = new ChatResponseBuilder(catalog, marketData, aiInsight);
        TickerSummary noPrice = new TickerSummary("AAPL", null, List.of(), null, null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(noPrice);

        ChatResponse resp = realBuilder.build(ResolutionOutcome.resolved("AAPL", "llm"));

        assertThat(resp.response())
                .as("P2: null Redis price must yield no-data response, not LLM number")
                .contains("AAPL");
        assertThat(resp.response().toLowerCase())
                .containsAnyOf("no data", "unavailable", "not available", "not actively");
        // aiInsight must NOT be queried — there is no price to anchor sentiment
        verify(aiInsight, never()).getSentiment(anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P3 — Exact-symbol preservation (Req 1.4 / design.md Property 3)
    // Suffixed catalog symbols must resolve via the deterministic preflight path
    // with zero LLM calls, regardless of surrounding message text.
    // ═══════════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "P3: token ''{0}'' → ''{1}'' via preflight, 0 LLM calls")
    @CsvSource({
            "BTC-USD, BTC-USD, Tell me about BTC-USD",
            "USDCHF=X, USDCHF=X, What is USDCHF=X?",
            "HDFCBANK.NS, HDFCBANK.NS, Price of HDFCBANK.NS",
            "RELIANCE.NS, RELIANCE.NS, RELIANCE.NS trend",
            "AAPL, AAPL, How is AAPL doing?"
    })
    void p3_exactCatalogSymbol_resolvesViaPreflight_zeroLlmCalls(
            String normalizeInput, String expectedTicker, String message) {
        when(catalog.normalize(normalizeInput)).thenReturn(Optional.of(expectedTicker));

        service.handle(new ChatRequest(message, null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().ticker())
                .as("P3: %s must resolve to %s", normalizeInput, expectedTicker)
                .isEqualTo(expectedTicker);
        assertThat(cap.getValue().source())
                .as("P3: must use deterministic preflight, not LLM")
                .isEqualTo("preflight");
        assertThat(stubClient.callCount())
                .as("P3: deterministic path must make zero LLM calls")
                .isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P4 — Single LLM call (Req 8.1, 8.2 / design.md Property 4)
    // At most one AssetResolutionClient.resolve() call per request.
    // Deterministic paths (explicit, preflight, discovery-shortcut) make zero.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p4_explicitTicker_zeroLlmCalls() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        service.handle(new ChatRequest("query", "AAPL"));
        assertThat(stubClient.callCount()).as("P4: explicit path → 0 LLM calls").isZero();
    }

    @Test
    void p4_preflightResolvable_zeroLlmCalls() {
        when(catalog.normalize("BTC-USD")).thenReturn(Optional.of("BTC-USD"));
        service.handle(new ChatRequest("BTC-USD price?", null));
        assertThat(stubClient.callCount()).as("P4: preflight path → 0 LLM calls").isZero();
    }

    @Test
    void p4_discoveryShortcut_zeroLlmCalls() {
        service.handle(new ChatRequest("what assets do you track?", null));
        assertThat(stubClient.callCount()).as("P4: discovery-shortcut → 0 LLM calls").isZero();
    }

    @Test
    void p4_llmPath_exactlyOneLlmCallPerRequest() {
        stubClient.whenMessage("Ethereum price",
                StubAssetResolutionClient.assetQuery("ETH-USD", "Ethereum"));
        when(catalog.isSupported("ETH-USD")).thenReturn(true);

        service.handle(new ChatRequest("Ethereum price", null));

        assertThat(stubClient.callCount()).as("P4: LLM path → exactly 1 call").isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P5 — No silent wrong pick (Req 5.1, 5.2 / design.md Property 5)
    // Multi-candidate and ambiguous messages must yield CLARIFICATION or
    // COMPARISON_REDIRECT. The system must never silently resolve to one of
    // several plausible candidates.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p5_twoCatalogSymbolsInMessage_comparisonRedirectNotSilentResolve() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));

        service.handle(new ChatRequest("AAPL and MSFT", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P5: two distinct symbols must redirect, never silently pick one")
                .isEqualTo(Outcome.COMPARISON_REDIRECT);
        assertThat(cap.getValue().candidates())
                .as("P5: both symbols must appear in the redirect")
                .containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(stubClient.callCount()).as("P5 + P4: no LLM on deterministic multi-candidate").isZero();
    }

    @Test
    void p5_compareKeywordWithSingleSymbol_doesNotSilentlyResolve() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("compare AAPL and Apple", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P5: comparison cue with single preflight symbol must NOT silently resolve")
                .isNotEqualTo(Outcome.RESOLVED);
    }

    @Test
    void p5_llmReturnsAmbiguousCandidates_clarificationWithAllCandidates() {
        stubClient.whenMessage("bank stock",
                new LlmResolution(Intent.ASSET_QUERY, List.of("bank"),
                        List.of(), List.of("JPM", "BAC", "C"), null, "multiple banks"));
        when(catalog.isSupported("JPM")).thenReturn(true);
        when(catalog.isSupported("BAC")).thenReturn(true);
        when(catalog.isSupported("C")).thenReturn(true);

        service.handle(new ChatRequest("bank stock", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P5: LLM ambiguous candidates must yield clarification, not random pick")
                .isEqualTo(Outcome.CLARIFICATION);
        assertThat(cap.getValue().candidates())
                .as("P5: all catalog-valid candidates must be surfaced for the user to choose")
                .containsExactlyInAnyOrder("JPM", "BAC", "C");
    }

    @Test
    void p5_singleCandidateWithNoComparisonCue_resolvesDeterministically() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        service.handle(new ChatRequest("AAPL", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P5: single unambiguous candidate must resolve (guard must not over-restrict)")
                .isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
    }


    // ═══════════════════════════════════════════════════════════════════════════════
    // P6 — Never-empty (Req 6.5 / design.md Property 6)
    // Every outcome path, including errors and no-data, must produce a non-null,
    // non-blank assistant message. The system must never return an empty response.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p6_allSixBuiltOutcomeTypes_responseNeverEmpty() {
        // Use a real ChatResponseBuilder to verify the never-empty contract end-to-end.
        ChatResponseBuilder realBuilder = new ChatResponseBuilder(catalog, marketData, aiInsight);
        when(marketData.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", null, List.of(), null, null)); // null price → no-data
        when(marketData.getMarketSummary()).thenReturn(Map.of());
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.find("MSFT")).thenReturn(Optional.of(MSFT));
        when(catalog.byCategory(null)).thenReturn(List.of(AAPL));

        List<ResolutionOutcome> outcomes = List.of(
                ResolutionOutcome.resolved("AAPL", "preflight"),       // null price → no-data branch
                ResolutionOutcome.noData("AAPL", "llm"),
                ResolutionOutcome.clarification(List.of("AAPL", "MSFT"), "preflight", null),
                ResolutionOutcome.discovery(null, "discovery-shortcut"),
                ResolutionOutcome.comparisonRedirect(List.of("AAPL", "MSFT"), "preflight"),
                ResolutionOutcome.greetingHelp("llm")
        );

        for (ResolutionOutcome outcome : outcomes) {
            ChatResponse resp = realBuilder.build(outcome);
            assertThat(resp.response())
                    .as("P6: response must be non-empty for outcome %s", outcome.outcome())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    void p6_emptyMessage_yieldsClarification_responseNonEmpty() {
        ChatResponse resp = service.handle(new ChatRequest("", null));

        assertThat(resp).as("P6: even an empty message must produce a ChatResponse").isNotNull();
        assertThat(resp.response()).as("P6: assistant response must be non-blank").isNotBlank();
    }

    @Test
    void p6_llmFailure_responseStillNonEmpty() {
        stubClient.alwaysThrow(new LlmResolutionException(Kind.UNAVAILABLE, "down"));

        ChatResponse resp = service.handle(new ChatRequest("something vague", null));

        assertThat(resp.response())
                .as("P6: LLM failure must still produce a non-empty assistant response")
                .isNotBlank();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P7 — Fallback safety (Req 6.1, 6.6 / design.md Property 7)
    // Deterministic resolution takes over when the LLM fails. Exact canonical symbols
    // and catalog-normalizable stems still resolve; natural-language names yield
    // clarification rather than crashing or silently resolving.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p7_llmUnavailable_exactSymbolInMessage_resolvesViaPreflight() {
        // Preflight catches BTC-USD BEFORE the LLM is ever called.
        // LLM failure is irrelevant — the symbol was already resolved deterministically.
        when(catalog.normalize("BTC-USD")).thenReturn(Optional.of("BTC-USD"));
        stubClient.alwaysThrow(new LlmResolutionException(Kind.UNAVAILABLE, "LLM down"));

        service.handle(new ChatRequest("Tell me about BTC-USD", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P7: exact symbol must resolve even when LLM is unavailable")
                .isEqualTo(Outcome.RESOLVED);
        assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
        assertThat(stubClient.callCount())
                .as("P7: LLM must not be called when preflight succeeds")
                .isZero();
    }

    @Test
    void p7_llmTimeout_naturalLanguageName_fallbackYieldsClarification() {
        // "Bitcoin" does not normalize; with LLM timing out, fallback cannot alias-resolve.
        stubClient.alwaysThrow(new LlmResolutionException(Kind.TIMEOUT, "timeout after 5s"));

        service.handle(new ChatRequest("Bitcoin", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P7: name-only query with LLM down must fall back to clarification")
                .isEqualTo(Outcome.CLARIFICATION);
        assertThat(cap.getValue().source())
                .as("P7: source must identify the fallback path for structured logs")
                .isEqualTo("fallback-exact");
    }

    @Test
    void p7_llmMalformed_comparisonGuardPreservedInFallback() {
        // Two normalizable symbols found by preflight → comparison redirect.
        // LLM is never reached, so malformed output cannot disrupt the guard.
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));
        stubClient.alwaysThrow(new LlmResolutionException(Kind.MALFORMED, "bad JSON"));

        service.handle(new ChatRequest("AAPL vs MSFT", null));

        ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
        verify(responseBuilder).build(cap.capture());
        assertThat(cap.getValue().outcome())
                .as("P7: comparison guard must be preserved regardless of LLM status")
                .isEqualTo(Outcome.COMPARISON_REDIRECT);
        assertThat(stubClient.callCount()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P8 — Determinism (Req 9.4 / design.md Property 8)
    // The preflight and fallback resolution paths are pure functions: identical inputs
    // produce identical ResolutionOutcome values on every invocation, regardless of
    // invocation order or prior request history.
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    void p8_preflightPath_sameInputProducesSameOutcomeRepeatedly() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        Outcome[] outcomes = new Outcome[3];
        String[] tickers  = new String[3];
        String[] sources  = new String[3];

        for (int i = 0; i < 3; i++) {
            ChatResponseBuilder b = mock(ChatResponseBuilder.class);
            when(b.build(any())).thenReturn(new ChatResponse("r"));
            ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
            new ChatResolutionService(catalog, new StubAssetResolutionClient(), b)
                    .handle(new ChatRequest("How is AAPL doing?", null));
            verify(b).build(cap.capture());
            outcomes[i] = cap.getValue().outcome();
            tickers[i]  = cap.getValue().ticker();
            sources[i]  = cap.getValue().source();
        }

        assertThat(outcomes[0]).as("P8: outcome is deterministic")
                .isEqualTo(outcomes[1]).isEqualTo(outcomes[2]);
        assertThat(tickers[0]).as("P8: ticker is deterministic")
                .isEqualTo(tickers[1]).isEqualTo(tickers[2]);
        assertThat(sources[0]).as("P8: source is deterministic")
                .isEqualTo(sources[1]).isEqualTo(sources[2]);
    }

    @Test
    void p8_fallbackPath_sameNonresolvableInput_alwaysYieldsSameClarification() {
        for (int i = 0; i < 3; i++) {
            StubAssetResolutionClient throwingStub = new StubAssetResolutionClient()
                    .alwaysThrow(new LlmResolutionException(Kind.UNAVAILABLE, "down"));
            ChatResponseBuilder b = mock(ChatResponseBuilder.class);
            when(b.build(any())).thenReturn(new ChatResponse("r"));
            ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
            new ChatResolutionService(catalog, throwingStub, b)
                    .handle(new ChatRequest("Unknown asset xyz", null));
            verify(b).build(cap.capture());
            assertThat(cap.getValue().outcome())
                    .as("P8 iter %d: fallback must deterministically produce clarification", i)
                    .isEqualTo(Outcome.CLARIFICATION);
            assertThat(cap.getValue().source())
                    .as("P8 iter %d: fallback source must be 'fallback-exact'", i)
                    .isEqualTo("fallback-exact");
        }
    }

    @Test
    void p8_explicitTicker_deterministicRegardlessOfFreeTextVariation() {
        // The explicit ticker field governs the outcome; the free-text message is irrelevant.
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        String[] messages = {"AAPL", "What is Apple?", "Tell me more", ""};

        for (String msg : messages) {
            ChatResponseBuilder b = mock(ChatResponseBuilder.class);
            when(b.build(any())).thenReturn(new ChatResponse("r"));
            ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
            new ChatResolutionService(catalog, new StubAssetResolutionClient(), b)
                    .handle(new ChatRequest(msg, "AAPL"));
            verify(b).build(cap.capture());
            assertThat(cap.getValue().outcome())
                    .as("P8: explicit ticker always resolves, message='%s'", msg)
                    .isEqualTo(Outcome.RESOLVED);
            assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
            assertThat(cap.getValue().source()).isEqualTo("explicit");
        }
    }
}
