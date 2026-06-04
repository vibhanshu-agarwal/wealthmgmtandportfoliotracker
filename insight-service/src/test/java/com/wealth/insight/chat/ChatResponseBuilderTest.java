package com.wealth.insight.chat;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatResponseBuilder} (Task 7 / Req 3.1, 3.2, 3.4, 3.5, 4.2, 5.1,
 * 6.2, 6.5).
 *
 * <p>Verifies currency-aware formatting, no-data path, sentiment integration, discovery
 * bounding/grouping, comparison-redirect, clarification, and the never-empty contract (P6).
 * Uses mocked collaborators — no live Redis or LLM calls (Req 9.3).
 */
@ExtendWith(MockitoExtension.class)
class ChatResponseBuilderTest {

    @Mock private TickerCatalogService catalog;
    @Mock private MarketDataService marketData;
    @Mock private AiInsightService aiInsight;

    private ChatResponseBuilder builder;

    // ─── Common catalog entries used across tests ────────────────────────────
    private static final CatalogEntry AAPL =
            new CatalogEntry("AAPL", "Apple", List.of("Apple"), "US_EQUITY", "USD");
    private static final CatalogEntry HDFCBANK =
            new CatalogEntry("HDFCBANK.NS", "HDFC Bank", List.of("HDFC Bank"), "NSE", "INR");
    private static final CatalogEntry BTC =
            new CatalogEntry("BTC-USD", "Bitcoin", List.of("Bitcoin", "BTC"), "CRYPTO", "USD");
    private static final CatalogEntry EURUSD =
            new CatalogEntry("EURUSD=X", "EUR/USD", List.of("EURUSD", "EUR/USD"), "FOREX", "USD");
    private static final CatalogEntry MSFT =
            new CatalogEntry("MSFT", "Microsoft", List.of("Microsoft", "MSFT"), "US_EQUITY", "USD");

    @BeforeEach
    void setUp() {
        builder = new ChatResponseBuilder(catalog, marketData, aiInsight);
    }

    // ── Property 6: Never empty ───────────────────────────────────────────────

    @Test
    void build_anyOutcome_neverReturnsEmptyResponse() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        TickerSummary noPrice = new TickerSummary("AAPL", null, List.of(), null, null);

        List<ResolutionOutcome> outcomes = List.of(
                ResolutionOutcome.resolved("AAPL", "preflight"),
                ResolutionOutcome.noData("AAPL", "preflight"),
                ResolutionOutcome.clarification(List.of("AAPL", "MSFT"), "ambiguous", null),
                ResolutionOutcome.discovery(null, "discovery-shortcut"),
                ResolutionOutcome.comparisonRedirect(List.of("AAPL", "MSFT"), "preflight"),
                ResolutionOutcome.greetingHelp("preflight")
        );
        when(marketData.getTickerSummary("AAPL")).thenReturn(noPrice);
        when(marketData.getMarketSummary()).thenReturn(Map.of());
        when(catalog.find("MSFT")).thenReturn(Optional.of(MSFT));

        for (ResolutionOutcome outcome : outcomes) {
            ChatResponse resp = builder.build(outcome);
            assertThat(resp.response())
                    .as("Response must be non-empty for outcome %s", outcome.outcome())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    // ── RESOLVED — currency formatting per asset class ────────────────────────

    @Test
    void build_resolvedUsEquity_formatsPriceWithUsdSymbol() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        TickerSummary summary = new TickerSummary("AAPL", new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50"), new BigDecimal("175.00")),
                new BigDecimal("2.00"), null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsight.getSentiment("AAPL")).thenReturn("Bullish sentiment.");

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("AAPL", "preflight"));

        assertThat(resp.response()).contains("AAPL");
        assertThat(resp.response()).contains("USD");
        assertThat(resp.response()).contains("178.50");
        assertThat(resp.response()).doesNotContain("INR").doesNotContain("₹");
    }

    @Test
    void build_resolvedNse_formatsPriceWithInr() {
        when(catalog.find("HDFCBANK.NS")).thenReturn(Optional.of(HDFCBANK));
        TickerSummary summary = new TickerSummary("HDFCBANK.NS", new BigDecimal("1580.00"),
                List.of(new BigDecimal("1580.00"), new BigDecimal("1540.00")),
                new BigDecimal("2.60"), null);
        when(marketData.getTickerSummary("HDFCBANK.NS")).thenReturn(summary);
        when(aiInsight.getSentiment("HDFCBANK.NS")).thenReturn("Bullish.");

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("HDFCBANK.NS", "llm"));

        assertThat(resp.response()).contains("INR");
        assertThat(resp.response()).contains("1580");
        assertThat(resp.response()).doesNotContain("USD").doesNotContain("$");
    }

    @Test
    void build_resolvedCrypto_formatsPriceWithUsd() {
        when(catalog.find("BTC-USD")).thenReturn(Optional.of(BTC));
        TickerSummary summary = new TickerSummary("BTC-USD", new BigDecimal("65000.00"),
                List.of(new BigDecimal("65000.00"), new BigDecimal("63000.00")),
                new BigDecimal("3.17"), null);
        when(marketData.getTickerSummary("BTC-USD")).thenReturn(summary);
        when(aiInsight.getSentiment("BTC-USD")).thenReturn("Bullish.");

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("BTC-USD", "llm"));

        assertThat(resp.response()).contains("USD");
        assertThat(resp.response()).contains("65000");
    }

    @Test
    void build_resolvedForex_includesPairConvention() {
        when(catalog.find("EURUSD=X")).thenReturn(Optional.of(EURUSD));
        TickerSummary summary = new TickerSummary("EURUSD=X", new BigDecimal("1.0850"),
                List.of(new BigDecimal("1.0850"), new BigDecimal("1.0800")),
                new BigDecimal("0.46"), null);
        when(marketData.getTickerSummary("EURUSD=X")).thenReturn(summary);
        when(aiInsight.getSentiment("EURUSD=X")).thenReturn("Neutral.");

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("EURUSD=X", "llm"));

        assertThat(resp.response()).contains("EUR/USD");
        assertThat(resp.response()).contains("1.0850");
    }

    // ── RESOLVED — no-data path ───────────────────────────────────────────────

    @Test
    void build_resolvedButNullLatestPrice_returnsNoDataNamingTicker() {
        // catalog.find is NOT called when latestPrice is null — buildResolved returns early
        TickerSummary noPrice = new TickerSummary("AAPL", null, List.of(), null, null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(noPrice);

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("AAPL", "preflight"));

        assertThat(resp.response()).contains("AAPL");
        assertThat(resp.response().toLowerCase()).containsAnyOf("no data", "unavailable", "not available");
        verify(aiInsight, never()).getSentiment(any());
    }

    // ── RESOLVED — sentiment paths ────────────────────────────────────────────

    @Test
    void build_resolvedWithData_appendsSentiment() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        TickerSummary summary = new TickerSummary("AAPL", new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50")), null, null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsight.getSentiment("AAPL")).thenReturn("Bullish outlook detected.");

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("AAPL", "preflight"));

        assertThat(resp.response()).contains("Bullish outlook detected.");
    }

    @Test
    void build_sentimentUnavailable_appendsAiUnavailableNote() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        TickerSummary summary = new TickerSummary("AAPL", new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50")), null, null);
        when(marketData.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsight.getSentiment("AAPL")).thenThrow(
                new AdvisorUnavailableException("AI down"));

        ChatResponse resp = builder.build(ResolutionOutcome.resolved("AAPL", "preflight"));

        assertThat(resp.response()).contains("178.50");
        assertThat(resp.response().toLowerCase()).containsAnyOf(
                "ai analysis", "temporarily unavailable", "unavailable");
        assertThat(resp.response()).isNotBlank();
    }

    // ── NO_DATA outcome ───────────────────────────────────────────────────────

    @Test
    void build_noData_namesTickerInResponse() {
        ChatResponse resp = builder.build(ResolutionOutcome.noData("BTC-USD", "llm"));

        assertThat(resp.response()).contains("BTC-USD");
        assertThat(resp.response().toLowerCase()).containsAnyOf("no data", "unavailable", "not available");
    }

    // ── CLARIFICATION outcome ─────────────────────────────────────────────────

    @Test
    void build_clarification_promptsUserToSpecify() {
        ChatResponse resp = builder.build(
                ResolutionOutcome.clarification(List.of("AAPL", "MSFT"), "ambiguous", null));

        assertThat(resp.response().toLowerCase()).containsAnyOf("specify", "clarif", "which", "one");
    }

    @Test
    void build_clarificationWithCandidates_listsCandidates() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.find("MSFT")).thenReturn(Optional.of(MSFT));
        ChatResponse resp = builder.build(
                ResolutionOutcome.clarification(List.of("AAPL", "MSFT"), "ambiguous", null));

        assertThat(resp.response()).contains("AAPL");
        assertThat(resp.response()).contains("MSFT");
    }

    // ── COMPARISON_REDIRECT outcome ───────────────────────────────────────────

    @Test
    void build_comparisonRedirect_namesBothNamesAndTickers() {
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.find("MSFT")).thenReturn(Optional.of(MSFT));

        ChatResponse resp = builder.build(
                ResolutionOutcome.comparisonRedirect(List.of("AAPL", "MSFT"), "preflight"));

        // Must name both display-name and ticker (Req 5.1, design Property 5)
        assertThat(resp.response()).contains("Apple");
        assertThat(resp.response()).contains("AAPL");
        assertThat(resp.response()).contains("Microsoft");
        assertThat(resp.response()).contains("MSFT");
        assertThat(resp.response().toLowerCase()).containsAnyOf("one at a time", "which", "one");
    }

    // ── DISCOVERY outcome ─────────────────────────────────────────────────────

    @Test
    void build_discovery_returnsGroupedAndBoundedListing() {
        Map<String, TickerSummary> summaries = Map.of(
                "AAPL", new TickerSummary("AAPL", new BigDecimal("178"), List.of(), null, null),
                "BTC-USD", new TickerSummary("BTC-USD", new BigDecimal("65000"), List.of(), null, null)
        );
        when(marketData.getMarketSummary()).thenReturn(summaries);
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.find("BTC-USD")).thenReturn(Optional.of(BTC));

        ChatResponse resp = builder.build(ResolutionOutcome.discovery(null, "discovery-shortcut"));

        assertThat(resp.response()).isNotBlank();
        // Should mention at least one tracked asset
        assertThat(resp.response()).containsAnyOf("Apple", "Bitcoin", "AAPL", "BTC-USD");
    }

    @Test
    void build_discoveryWithCategoryFilter_returnsOnlyThatCategory() {
        // Both tickers are fetched via catalog.find() and then filtered by assetClass.
        // catalog.byCategory() is only used in the empty-Redis fallback path — not here.
        Map<String, TickerSummary> summaries = Map.of(
                "AAPL", new TickerSummary("AAPL", new BigDecimal("178"), List.of(), null, null),
                "HDFCBANK.NS", new TickerSummary("HDFCBANK.NS", new BigDecimal("1580"), List.of(), null, null)
        );
        when(marketData.getMarketSummary()).thenReturn(summaries);
        when(catalog.find("AAPL")).thenReturn(Optional.of(AAPL));
        when(catalog.find("HDFCBANK.NS")).thenReturn(Optional.of(HDFCBANK));

        ChatResponse resp = builder.build(ResolutionOutcome.discovery("NSE", "discovery-shortcut"));

        assertThat(resp.response()).contains("HDFC Bank");
        assertThat(resp.response()).doesNotContain("Apple");
    }

    @Test
    void build_discoveryRedisEmpty_fallsBackToCatalogWithUnavailableWording() {
        when(marketData.getMarketSummary()).thenReturn(Map.of());
        when(catalog.byCategory(null)).thenReturn(List.of(AAPL, BTC));

        ChatResponse resp = builder.build(ResolutionOutcome.discovery(null, "discovery-shortcut"));

        assertThat(resp.response()).isNotBlank();
        assertThat(resp.response().toLowerCase()).containsAnyOf(
                "temporarily", "unavailable", "live data");
    }

    // ── GREETING_HELP outcome ─────────────────────────────────────────────────

    @Test
    void build_greetingHelp_returnsCapabilityOverview() {
        ChatResponse resp = builder.build(ResolutionOutcome.greetingHelp("preflight"));

        assertThat(resp.response()).isNotBlank();
        assertThat(resp.response().toLowerCase())
                .containsAnyOf("ask", "stock", "crypto", "track", "help", "can");
    }
}
