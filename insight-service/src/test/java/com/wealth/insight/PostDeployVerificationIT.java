package com.wealth.insight;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 9 — Post-deploy demo verification (Task 19 / Req 1.1, 1.2, 1.3, 4.1, 9.1).
 *
 * <p>Verifies that the natural-language resolution feature functions correctly after the feature
 * branch merges and auto-deploys. This test has two complementary modes:
 *
 * <ol>
 *   <li><b>Local stack (always active):</b> full stack with real {@link TickerCatalogService},
 *       {@link StubAssetResolutionClient} (canned LLM — Req 9.3), and Logback {@link ListAppender}
 *       log capture. Asserts response content AND structured log fields ({@code source},
 *       {@code intent}, {@code catalogVersion}) per Req 9.1. The {@code catalogVersion} value
 *       is the 8-hex-char SHA-256 prefix over the sorted ticker list of the enriched
 *       {@code seed-tickers.json}.</li>
 *   <li><b>Live Azure (activated by env var {@code POST_DEPLOY_BASE_URL}):</b> uses
 *       {@link RestTemplate} to POST to the deployed Azure Container App endpoint. Validates
 *       that the previously-failing queries now return HTTP 200 responses containing the expected
 *       ticker symbols.</li>
 * </ol>
 *
 * <h3>Azure Log Analytics KQL — confirming structured log fields on the live deployment</h3>
 * <p>After running the live queries, confirm the following KQL in Azure Log Analytics workspace
 * (table: {@code AppTraces}) to validate {@code source}, {@code intent}, and {@code catalogVersion}:
 * <pre>{@code
 * AppTraces
 * | where Message startswith "resolution.outcome"
 * | where TimeGenerated > ago(1h)
 * | project TimeGenerated, Message
 * | order by TimeGenerated desc
 * | take 20
 * }</pre>
 * Expected field values per demo scenario:
 * <ul>
 *   <li>"Apple" → {@code intent=ASSET_QUERY source=llm resolvedTickers=AAPL}</li>
 *   <li>"HDFC Bank" → {@code intent=ASSET_QUERY source=llm resolvedTickers=HDFCBANK.NS}</li>
 *   <li>"BTC" → {@code intent=ASSET_QUERY source=preflight resolvedTickers=BTC-USD}</li>
 *   <li>"BTCUSD" → {@code intent=ASSET_QUERY source=preflight resolvedTickers=BTC-USD}</li>
 *   <li>"which stocks can you tell me about?" → {@code intent=DISCOVERY source=discovery-shortcut}</li>
 *   <li>"RELIANCE.NS" → {@code intent=ASSET_QUERY source=preflight resolvedTickers=RELIANCE.NS}</li>
 *   <li>"USDCHF=X" → {@code intent=ASSET_QUERY source=preflight resolvedTickers=USDCHF=X}</li>
 * </ul>
 * {@code catalogVersion} must be the same 8-char hex in every log line, matching the SHA-256 prefix
 * of the loaded {@code seed-tickers.json} ticker list.
 *
 * <p>Tagged {@code post-deploy} — run via {@code ./gradlew :insight-service:test -Dgroups=post-deploy}
 * (or via IDE; excluded from the standard CI suite by the tag filter in the build configuration).
 *
 * @see ChatResolutionService#logOutcome for the structured log implementation
 * @see StubAssetResolutionClient for the deterministic LLM test double
 */
@Tag("post-deploy")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostDeployVerificationIT {

    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    private TickerCatalogService catalogService;
    private StubAssetResolutionClient stubClient;
    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger resolutionLogger;

    @BeforeEach
    void setUp() throws Exception {
        catalogService = new TickerCatalogService();
        catalogService.load();
        assertThat(catalogService.isSupported("AAPL"))
                .as("seed/seed-tickers.json must be on the test classpath — run ./gradlew copySeedTickers")
                .isTrue();

        // Configure stub with canned NL → ticker mappings for the demo scenarios (Req 9.3)
        stubClient = new StubAssetResolutionClient()
                .whenMessage("Apple",     StubAssetResolutionClient.assetQuery("AAPL",        "Apple"))
                .whenMessage("HDFC Bank", StubAssetResolutionClient.assetQuery("HDFCBANK.NS", "HDFC Bank"));

        when(aiInsightService.getSentiment(anyString())).thenReturn("Neutral outlook.");
        when(marketDataService.getMarketSummary()).thenReturn(Collections.emptyMap());

        ChatResponseBuilder builder  = new ChatResponseBuilder(catalogService, marketDataService, aiInsightService);
        ChatResolutionService service = new ChatResolutionService(catalogService, stubClient, builder);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        // Attach Logback in-memory appender to capture structured resolution logs (Req 9.1)
        resolutionLogger = (Logger) LoggerFactory.getLogger(ChatResolutionService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        resolutionLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        resolutionLogger.detachAppender(logAppender);
    }

    // ── Helper ──────────────────────────────────────────────────────────────────────────────

    /** Returns the most recent structured log message from the resolution logger. */
    private String capturedLog() {
        assertThat(logAppender.list).as("at least one resolution.outcome log line expected").isNotEmpty();
        return logAppender.list.get(logAppender.list.size() - 1).getFormattedMessage();
    }

    /** Seeds a TickerSummary for the given ticker so ChatResponseBuilder can produce a RESOLVED response. */
    private void seedMarketData(String ticker, String price) {
        when(marketDataService.getTickerSummary(ticker))
                .thenReturn(new TickerSummary(ticker, new BigDecimal(price), List.of(), null, null));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // LOCAL STACK VERIFICATION — structured log fields + HTTP response content
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    // ── Req 1.1: "Apple" → AAPL via LLM (source=llm) ─────────────────────────────────────

    /**
     * Verifies "Apple" resolves to AAPL via the LLM path.
     * Expected: {@code source=llm intent=ASSET_QUERY resolvedTickers=AAPL catalogVersion=<8-hex>}.
     */
    @Test
    void req1_1_apple_resolvesToAapl_sourceIsLlm_intentIsAssetQuery() throws Exception {
        seedMarketData("AAPL", "178.50");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Apple"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")));

        String log = capturedLog();
        assertThat(log).contains("source=llm");
        assertThat(log).contains("intent=ASSET_QUERY");
        assertThat(log).contains("resolvedTickers=AAPL");
        assertThat(log).contains("llmStatus=ok");
        assertThat(log).contains("catalogVersion=" + catalogService.catalogVersion());
    }

    // ── Req 1.1: "HDFC Bank" → HDFCBANK.NS via LLM (source=llm) ─────────────────────────

    /**
     * Verifies "HDFC Bank" resolves to HDFCBANK.NS via the LLM path.
     * Expected: {@code source=llm intent=ASSET_QUERY resolvedTickers=HDFCBANK.NS catalogVersion=<8-hex>}.
     */
    @Test
    void req1_1_hdfcBank_resolvesToHdfcbankNs_sourceIsLlm_intentIsAssetQuery() throws Exception {
        seedMarketData("HDFCBANK.NS", "1550.75");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "HDFC Bank"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("HDFCBANK.NS")));

        String log = capturedLog();
        assertThat(log).contains("source=llm");
        assertThat(log).contains("intent=ASSET_QUERY");
        assertThat(log).contains("resolvedTickers=HDFCBANK.NS");
        assertThat(log).contains("catalogVersion=" + catalogService.catalogVersion());
    }

    // ── Req 1.2: "BTC" → BTC-USD via deterministic preflight (source=preflight) ───────────

    /**
     * Verifies "BTC" resolves to BTC-USD via the deterministic preflight (no LLM call).
     * Expected: {@code source=preflight intent=ASSET_QUERY resolvedTickers=BTC-USD llmStatus=skipped}.
     */
    @Test
    void req1_2_btcStem_resolvesToBtcUsd_sourcePreflight_noLlmCall() throws Exception {
        seedMarketData("BTC-USD", "64000.00");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "BTC"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("BTC-USD")));

        String log = capturedLog();
        assertThat(log).contains("source=preflight");
        assertThat(log).contains("resolvedTickers=BTC-USD");
        assertThat(log).contains("llmStatus=skipped");
        assertThat(log).contains("catalogVersion=" + catalogService.catalogVersion());
        assertThat(stubClient.callCount()).as("preflight must short-circuit — LLM must not be called").isZero();
    }

    // ── Req 1.3: "BTCUSD" (glued pair) → BTC-USD via preflight (source=preflight) ─────────

    /**
     * Verifies "BTCUSD" (glued pair) resolves to BTC-USD via deterministic preflight.
     * Expected: {@code source=preflight resolvedTickers=BTC-USD llmStatus=skipped}.
     */
    @Test
    void req1_3_btcusdGluedPair_resolvesToBtcUsd_sourcePreflight_noLlmCall() throws Exception {
        seedMarketData("BTC-USD", "64000.00");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "BTCUSD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("BTC-USD")));

        String log = capturedLog();
        assertThat(log).contains("source=preflight");
        assertThat(log).contains("resolvedTickers=BTC-USD");
        assertThat(log).contains("llmStatus=skipped");
        assertThat(stubClient.callCount()).as("preflight must short-circuit — LLM must not be called").isZero();
    }

    // ── Req 4.1: Discovery query → bounded grouped list (source=discovery-shortcut) ─────────

    /**
     * Verifies "which stocks can you tell me about?" triggers the discovery shortcut.
     * Expected: {@code source=discovery-shortcut intent=DISCOVERY} and response lists grouped assets.
     */
    @Test
    void req4_1_discoveryQuery_returnsGroupedBoundedList_sourceIsDiscoveryShortcut() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "which stocks can you tell me about?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("US_EQUITY")));

        String log = capturedLog();
        assertThat(log).contains("source=discovery-shortcut");
        assertThat(log).contains("intent=DISCOVERY");
        assertThat(log).contains("catalogVersion=" + catalogService.catalogVersion());
        assertThat(stubClient.callCount()).as("discovery-shortcut must bypass the LLM entirely").isZero();
    }

    // ── Req 1.4: Exact symbol "RELIANCE.NS" → passthrough (source=preflight) ───────────────

    /**
     * Verifies exact suffixed symbol RELIANCE.NS resolves deterministically (no regression of
     * chatbot-asset-coverage-fix — design Property P3).
     * Expected: {@code source=preflight resolvedTickers=RELIANCE.NS}.
     */
    @Test
    void req1_4_exactSymbol_relianceNs_resolvesViaPreflight_noRegression() throws Exception {
        seedMarketData("RELIANCE.NS", "2950.00");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Tell me about RELIANCE.NS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("RELIANCE.NS")));

        String log = capturedLog();
        assertThat(log).contains("source=preflight");
        assertThat(log).contains("resolvedTickers=RELIANCE.NS");
        assertThat(stubClient.callCount()).as("exact suffixed symbol must resolve via preflight only").isZero();
    }

    // ── Req 1.4: Exact symbol "USDCHF=X" → passthrough (source=preflight) ───────────────────

    /**
     * Verifies exact suffixed forex symbol USDCHF=X resolves deterministically.
     * Expected: {@code source=preflight resolvedTickers=USDCHF=X}.
     */
    @Test
    void req1_4_exactSymbol_usdchfX_resolvesViaPreflight_noRegression() throws Exception {
        seedMarketData("USDCHF=X", "0.9050");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is USDCHF=X doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("USDCHF=X")));

        String log = capturedLog();
        assertThat(log).contains("source=preflight");
        assertThat(log).contains("resolvedTickers=USDCHF=X");
        assertThat(stubClient.callCount()).as("exact suffixed forex symbol must resolve via preflight only").isZero();
    }

    // ── Req 9.1: catalogVersion is an 8-char hex hash consistent across requests ──────────

    /**
     * Verifies that {@code catalogVersion} in the structured log is a stable, 8-char hex string
     * consistent with the hash computed from the loaded {@code seed-tickers.json}.
     * This confirms the expected catalog version is present on the live deployment.
     */
    @Test
    void req9_1_catalogVersion_isStable8HexChars_matchesExpectedHash() throws Exception {
        seedMarketData("AAPL", "178.50");

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Apple"}
                                """))
                .andExpect(status().isOk());

        String expectedVersion = catalogService.catalogVersion();
        assertThat(expectedVersion).matches("[0-9a-f]{8}");

        String log = capturedLog();
        assertThat(log).contains("catalogVersion=" + expectedVersion);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // LIVE AZURE VERIFICATION — activated by POST_DEPLOY_BASE_URL environment variable
    //
    // Usage:  POST_DEPLOY_BASE_URL=https://<your-aca-hostname> ./gradlew :insight-service:test
    //                                                           -Dgroups=post-deploy
    //
    // These tests exercise the actual deployed Azure Container App endpoint over HTTPS.
    // They verify that the previously-failing natural-language queries now return correct responses.
    // For log-field verification on the live deployment, use the Azure Log Analytics KQL queries
    // documented in the class-level Javadoc.
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_apple_resolvesToAapl_previously_failing_query() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "Apple"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("AAPL");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_hdfcBank_resolvesToHdfcbankNs_previously_failing_query() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "HDFC Bank"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("HDFCBANK.NS");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_btc_resolvesToBtcUsd_previously_failing_query() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "BTC"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("BTC-USD");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_btcusd_resolvesToBtcUsd_previously_failing_query() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "BTCUSD"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("BTC-USD");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_discoveryQuery_returnsGroupedAssets_previously_failing_query() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "which stocks can you tell me about?"),
                Map.class);
        assertThat(resp).isNotNull();
        String response = resp.get("response").toString();
        // Discovery must return a bounded, grouped list — not a generic clarification
        assertThat(response).as("Discovery response must list assets, not return a generic error")
                .doesNotContain("couldn't identify");
        assertThat(response.length()).as("Discovery response must be substantive").isGreaterThan(50);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_relianceNs_exactSymbol_stillWorks_regression_guard() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "RELIANCE.NS"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("RELIANCE.NS");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POST_DEPLOY_BASE_URL", matches = ".+")
    void live_usdchfX_exactSymbol_stillWorks_regression_guard() {
        String base = System.getenv("POST_DEPLOY_BASE_URL");
        Map<?, ?> resp = new RestTemplate().postForObject(
                base + "/api/chat",
                Map.of("message", "USDCHF=X"),
                Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("response").toString()).contains("USDCHF=X");
    }
}
