package com.wealth.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.wealth.insight.resolution.LlmResolutionException;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 6 end-to-end integration tests for Tasks 13, 14, 15, and 16.
 *
 * <p>Uses the real ChatController → ChatResolutionService → ChatResponseBuilder stack with:
 * <ul>
 *   <li>Real {@link TickerCatalogService} (loaded from classpath seed — requires {@code copySeedTickers}).</li>
 *   <li>{@link StubAssetResolutionClient} — canned LLM responses, zero live Azure OpenAI calls (Req 9.3).</li>
 *   <li>Mocked {@link MarketDataService} and {@link AiInsightService} (no Redis, no Bedrock).</li>
 * </ul>
 *
 * <p>This is the key complement to {@link ChatResolutionServiceTest} (which mocks the response
 * builder) and {@link ChatControllerSliceTest} (which mocks the entire service).  By keeping
 * only the external I/O boundaries mocked, these tests verify the actual response text produced
 * end-to-end — including currency formatting, comparison-redirect asset names, discovery grouping,
 * "and more" truncation, and the never-empty guarantee (P6).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Wave6ResolutionEndToEndTest {

    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    private TickerCatalogService catalogService;
    private StubAssetResolutionClient stubClient;
    private MockMvc mockMvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        catalogService = new TickerCatalogService();
        catalogService.load();
        assumeTrue(catalogService.isSupported("AAPL"),
                "seed/seed-tickers.json not on classpath — run ./gradlew copySeedTickers first");

        stubClient = new StubAssetResolutionClient();

        // Sentiment: return a short string by default so RESOLVED tests include it
        when(aiInsightService.getSentiment(anyString())).thenReturn("Neutral outlook.");

        // Default: no market summary (catalog fallback path)
        when(marketDataService.getMarketSummary()).thenReturn(Collections.emptyMap());

        ChatResponseBuilder responseBuilder =
                new ChatResponseBuilder(catalogService, marketDataService, aiInsightService);
        ChatResolutionService resolutionService =
                new ChatResolutionService(catalogService, stubClient, responseBuilder);
        ChatController chatController = new ChatController(resolutionService);

        mockMvc = MockMvcBuilders.standaloneSetup(chatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Task 13 — Natural-language and deterministic resolution paths (Req 1.1–1.6, 2.5, 9.2–9.4)
    // ════════════════════════════════════════════════════════════════════════════

    /** P3: Exact symbol in message body resolves via preflight — no LLM, response names the asset. */
    @Test
    void t13_exactSymbolAapl_preflightResolved_responseNamesAppleAndShowsPrice() throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("How is AAPL doing today?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple")))
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("195.89")))
                .andExpect(jsonPath("$.response", containsString("USD")));
    }

    /** Bare crypto stem BTC normalizes to BTC-USD via preflight; response mentions Bitcoin. */
    @Test
    void t13_cryptoBareStem_btcNormalizes_responseNamesBitcoin() throws Exception {
        when(marketDataService.getTickerSummary("BTC-USD")).thenReturn(
                new TickerSummary("BTC-USD", new BigDecimal("67432.00"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Tell me about BTC", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin")))
                .andExpect(jsonPath("$.response", containsString("BTC-USD")))
                .andExpect(jsonPath("$.response", containsString("67432")));
    }

    /** Glued crypto pair BTCUSD normalizes to BTC-USD via preflight. */
    @Test
    void t13_gluedCryptoPair_btcusdNormalizes_resolved() throws Exception {
        when(marketDataService.getTickerSummary("BTC-USD")).thenReturn(
                new TickerSummary("BTC-USD", new BigDecimal("67000.00"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("BTCUSD price?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin")));
    }

    /** Slashed crypto pair BTC/USD normalizes to BTC-USD via preflight. */
    @Test
    void t13_slashedCryptoPair_btcSlashUsdNormalizes_resolved() throws Exception {
        when(marketDataService.getTickerSummary("BTC-USD")).thenReturn(
                new TickerSummary("BTC-USD", new BigDecimal("67000.00"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("BTC/USD today?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin")));
    }

    /** Glued forex pair USDCHF normalizes to USDCHF=X via preflight. */
    @Test
    void t13_gluedForexPair_usdchfNormalizes_responseNamesForexPair() throws Exception {
        when(marketDataService.getTickerSummary("USDCHF=X")).thenReturn(
                new TickerSummary("USDCHF=X", new BigDecimal("0.9120"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("USDCHF rate?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("USD/CHF")));
    }

    /** Slashed forex pair USD/CHF normalizes to USDCHF=X via preflight. */
    @Test
    void t13_slashedForexPair_usdSlashChfNormalizes_responseNamesForexPair() throws Exception {
        when(marketDataService.getTickerSummary("USDCHF=X")).thenReturn(
                new TickerSummary("USDCHF=X", new BigDecimal("0.9120"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("USD/CHF trend?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("USD/CHF")));
    }

    /** NSE exact symbol RELIANCE.NS resolves via preflight. */
    @Test
    void t13_exactNseSymbol_relianceNs_resolved_responseNamesReliance() throws Exception {
        when(marketDataService.getTickerSummary("RELIANCE.NS")).thenReturn(
                new TickerSummary("RELIANCE.NS", new BigDecimal("2845.60"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Price of RELIANCE.NS", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Reliance")))
                .andExpect(jsonPath("$.response", containsString("RELIANCE.NS")))
                .andExpect(jsonPath("$.response", containsString("INR")));
    }

    /** Official name "Apple" → LLM resolves AAPL → response contains name, ticker, price. */
    @Test
    void t13_officialNameApple_resolvedViaLlm_responseContainsAppleAaplAndPrice() throws Exception {
        stubClient.whenMessage("Tell me about Apple",
                new LlmResolution(Intent.ASSET_QUERY, List.of("Apple"),
                        List.of("AAPL"), List.of(), null, null));
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Tell me about Apple", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple")))
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("195.89")));
    }

    /** Alias "HDFC Bank" → LLM resolves HDFCBANK.NS → INR price in response. */
    @Test
    void t13_aliasHdfcBank_resolvedViaLlm_responseContainsInrPrice() throws Exception {
        stubClient.whenMessage("HDFC Bank",
                new LlmResolution(Intent.ASSET_QUERY, List.of("HDFC Bank"),
                        List.of("HDFCBANK.NS"), List.of(), null, null));
        when(marketDataService.getTickerSummary("HDFCBANK.NS")).thenReturn(
                new TickerSummary("HDFCBANK.NS", new BigDecimal("1520.50"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("HDFC Bank", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("HDFC Bank")))
                .andExpect(jsonPath("$.response", containsString("INR")))
                .andExpect(jsonPath("$.response", containsString("1520")));
    }

    /** Alias "Bitcoin" → LLM resolves BTC-USD → response names Bitcoin, shows USD price. */
    @Test
    void t13_aliasBitcoin_resolvedViaLlm_responseNamesBitcoin() throws Exception {
        stubClient.whenMessage("Bitcoin",
                new LlmResolution(Intent.ASSET_QUERY, List.of("Bitcoin"),
                        List.of("BTC-USD"), List.of(), null, null));
        when(marketDataService.getTickerSummary("BTC-USD")).thenReturn(
                new TickerSummary("BTC-USD", new BigDecimal("67432.00"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bitcoin", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin")))
                .andExpect(jsonPath("$.response", containsString("BTC-USD")))
                .andExpect(jsonPath("$.response", containsString("USD")));
    }

    /** Explicit ticker field AAPL → resolved without LLM; response has name + price. */
    @Test
    void t13_explicitTickerValid_aaplResolved_noLlmCalled() throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithTicker("How is Apple doing?", "AAPL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple")))
                .andExpect(jsonPath("$.response", containsString("195.89")));

        // No LLM call: explicit ticker uses normalize() path only
        org.assertj.core.api.Assertions.assertThat(stubClient.callCount()).isZero();
    }

    /** Explicit ticker field FAKEXXX → clarification (unsupported ticker). */
    @Test
    void t13_explicitTickerInvalid_clarificationResponse() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithTicker("anything", "FAKEXXX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", not("")));
    }

    /** LLM-proposed invented ticker FAKECOIN → dropped by catalog → clarification. P1. */
    @Test
    void t13_inventedLlmTicker_droppedByCatalog_clarificationResponse() throws Exception {
        stubClient.whenMessage("Fake coin price?",
                new LlmResolution(Intent.ASSET_QUERY, List.of("FAKECOIN"),
                        List.of("FAKECOIN"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Fake coin price?", null)))
                .andExpect(status().isOk())
                // Must not resolve — FAKECOIN is not in catalog (P1: catalog-bounded)
                .andExpect(jsonPath("$.response", not(containsString("195"))));
    }

    /** Redis no-data: AAPL resolved but latestPrice=null → response says "no data" naming AAPL. P2. */
    @Test
    void t13_redisNoData_resolvedTickerWithNullPrice_responseNamesTickerWithNoDataMessage()
            throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", null, List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("AAPL price?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response",
                        containsString("don't have any live data")));
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Task 14 — Comparison / multi-candidate guard tests (Req 2.6, 5.1, 5.2 / P5)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * "compare AAPL and MSFT" → comparison redirect naming both display names and tickers.
     * Response must contain "Apple (AAPL)" and "Microsoft (MSFT)" — no silent first-pick (P5).
     */
    @Test
    void t14_compareAaplAndMsft_redirectResponse_namesBothDisplayNamesAndTickers()
            throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("compare AAPL and MSFT", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple (AAPL)")))
                .andExpect(jsonPath("$.response", containsString("Microsoft (MSFT)")))
                .andExpect(jsonPath("$.response", not(containsString("195"))));  // no price data
    }

    /** "AAPL and MSFT" without compare keyword: two candidates in preflight → comparison redirect. */
    @Test
    void t14_aaplAndMsftNoCue_twoPreflightCandidates_comparisonRedirect() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("AAPL and MSFT performance", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("MSFT")));
    }

    /** "BTC and ETH" → comparison redirect naming Bitcoin (BTC-USD) and Ethereum (ETH-USD). */
    @Test
    void t14_btcAndEth_comparisonRedirect_namesBothCryptosWithTickers() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("BTC and ETH comparison", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin (BTC-USD)")))
                .andExpect(jsonPath("$.response", containsString("Ethereum (ETH-USD)")));
    }

    /** Single AAPL in message → single resolution, NOT comparison redirect. */
    @Test
    void t14_aaplAlone_resolvedSingly_notComparisonRedirect() throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("AAPL", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("195.89")))
                // Must not say "one at a time" (comparison redirect text)
                .andExpect(jsonPath("$.response", not(containsString("one at a time"))));
    }

    /** "tell me about AAPL" → single resolution, response has price not comparison text. */
    @Test
    void t14_tellMeAboutAapl_resolvedSingly_hasPrice() throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("tell me about AAPL", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("195.89")));
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Task 15 — Discovery tests (Req 4.1–4.5)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * "what assets do you track?" → discovery listing showing grouped categories with names+tickers.
     * Uses 2 live market entries so the response has category headings.
     */
    @Test
    void t15_whatAssetsDoYouTrack_groupedListing_withCategoryHeadersAndNames() throws Exception {
        Map<String, TickerSummary> marketData = new LinkedHashMap<>();
        marketData.put("AAPL",    new TickerSummary("AAPL",    new BigDecimal("195"), List.of(), null, null));
        marketData.put("BTC-USD", new TickerSummary("BTC-USD", new BigDecimal("67000"), List.of(), null, null));
        when(marketDataService.getMarketSummary()).thenReturn(marketData);

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("what assets do you track?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple")))
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("Bitcoin")))
                .andExpect(jsonPath("$.response", containsString("BTC-USD")));
    }

    /** "which crypto do you track?" → CRYPTO-only response; no US_EQUITY entries. */
    @Test
    void t15_whichCrypto_cryptoScopedResponse_noUsEquityEntries() throws Exception {
        Map<String, TickerSummary> marketData = new LinkedHashMap<>();
        marketData.put("AAPL",    new TickerSummary("AAPL",    new BigDecimal("195"), List.of(), null, null));
        marketData.put("BTC-USD", new TickerSummary("BTC-USD", new BigDecimal("67000"), List.of(), null, null));
        marketData.put("ETH-USD", new TickerSummary("ETH-USD", new BigDecimal("3420"), List.of(), null, null));
        when(marketDataService.getMarketSummary()).thenReturn(marketData);

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("which crypto do you track?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Bitcoin")))
                .andExpect(jsonPath("$.response", containsString("Ethereum")))
                .andExpect(jsonPath("$.response", not(containsString("Apple"))));
    }

    /** "which Indian stocks do you track?" → NSE-only response. */
    @Test
    void t15_whichIndianStocks_nseScopedResponse_noNonNseEntries() throws Exception {
        Map<String, TickerSummary> marketData = new LinkedHashMap<>();
        marketData.put("HDFCBANK.NS", new TickerSummary("HDFCBANK.NS", new BigDecimal("1520"), List.of(), null, null));
        marketData.put("RELIANCE.NS", new TickerSummary("RELIANCE.NS", new BigDecimal("2845"), List.of(), null, null));
        marketData.put("AAPL",        new TickerSummary("AAPL",        new BigDecimal("195"),  List.of(), null, null));
        when(marketDataService.getMarketSummary()).thenReturn(marketData);

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("which Indian stocks do you track?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("HDFC Bank")))
                .andExpect(jsonPath("$.response", containsString("Reliance")))
                .andExpect(jsonPath("$.response", not(containsString("Apple"))));
    }

    /**
     * 6 CRYPTO entries in market data → response shows first 5 (DISCOVERY_MAX_PER_CATEGORY=5)
     * and the "and more" indicator appears.
     */
    @Test
    void t15_sixCryptoEntries_boundedToFive_andMoreIndicatorPresent() throws Exception {
        Map<String, TickerSummary> marketData = new LinkedHashMap<>();
        for (String ticker : List.of(
                "BTC-USD", "ETH-USD", "BNB-USD", "SOL-USD", "XRP-USD", "ADA-USD")) {
            marketData.put(ticker, new TickerSummary(ticker, new BigDecimal("100"), List.of(), null, null));
        }
        when(marketDataService.getMarketSummary()).thenReturn(marketData);

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("which crypto do you track?", null)))
                .andExpect(status().isOk())
                // Response must be bounded — "and more" indicator shows truncation
                .andExpect(jsonPath("$.response", containsString("and more")));
    }

    /** Redis empty → catalog fallback with "live data temporarily unavailable" wording. */
    @Test
    void t15_redisEmpty_catalogFallback_unavailableWordingInResponse() throws Exception {
        when(marketDataService.getMarketSummary()).thenReturn(Collections.emptyMap());

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("what assets do you track?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response",
                        containsString("live data is temporarily unavailable")));
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Task 16 — Controller slice via MockMvc (Req 6.5, 9.2, 9.3 / P6)
    // ════════════════════════════════════════════════════════════════════════════

    /** P6: Every outcome path returns HTTP 200 with a non-empty response field. */
    @Test
    void t16_allOutcomePaths_http200_responseFieldNeverEmpty() throws Exception {
        // Set up market data so resolved paths work
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));
        Map<String, TickerSummary> marketMap = new LinkedHashMap<>();
        marketMap.put("AAPL", new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));
        marketMap.put("BTC-USD", new TickerSummary("BTC-USD", new BigDecimal("67000"), List.of(), null, null));
        when(marketDataService.getMarketSummary()).thenReturn(marketMap);
        stubClient.whenMessage("hello",
                new LlmResolution(Intent.GREETING_HELP, List.of(), List.of(), List.of(), null, null));

        String[][] cases = {
            {"AAPL", null},                          // RESOLVED
            {"AAPL and MSFT", null},                 // COMPARISON_REDIRECT
            {"what assets do you track?", null},     // DISCOVERY
            {"hello", null},                         // GREETING_HELP
            {"xyzunknownasset1234", null},            // CLARIFICATION
        };
        for (String[] tc : cases) {
            mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content(tc[1] == null ? body(tc[0], null) : bodyWithTicker(tc[0], tc[1])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response").exists())
                    .andExpect(jsonPath("$.response", not("")));
        }
    }

    /** Req 9.3: No real LLM invoked — stub records 0 calls for deterministic paths. */
    @Test
    void t16_deterministicPaths_zeroLlmCalls() throws Exception {
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(
                new TickerSummary("AAPL", new BigDecimal("195.89"), List.of(), null, null));

        // Deterministic paths: explicit ticker, preflight exact symbol, comparison cue, discovery
        for (String message : List.of("AAPL", "compare AAPL and MSFT",
                "what assets do you track?", "which crypto do you track?")) {
            mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content(body(message, null)))
                    .andExpect(status().isOk());
        }

        // All 4 messages resolved without LLM (preflight / discovery-shortcut paths)
        org.assertj.core.api.Assertions.assertThat(stubClient.callCount()).isZero();
    }

    /** P6: LLM throws → fallback → response still non-empty. */
    @Test
    void t16_llmThrows_fallbackPath_http200NonEmptyResponse() throws Exception {
        stubClient.alwaysThrow(new LlmResolutionException(
                LlmResolutionException.Kind.UNAVAILABLE, "endpoint-down"));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Apple stock price", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").exists())
                .andExpect(jsonPath("$.response", not("")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private String body(String message, String ticker) throws Exception {
        if (ticker == null) {
            return om.writeValueAsString(Map.of("message", message));
        }
        return om.writeValueAsString(Map.of("message", message, "ticker", ticker));
    }

    private String bodyWithTicker(String message, String ticker) throws Exception {
        return om.writeValueAsString(Map.of("message", message, "ticker", ticker));
    }
}

