package com.wealth.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.TickerSummary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Property 3: Preservation — Plain-symbol resolution and resolver responses unchanged.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4</b>
 *
 * <p>These are <b>preservation</b> property tests for the chatbot-asset-coverage-fix bugfix
 * (bug-condition methodology). They encode the BASELINE behavior of
 * {@link ChatController#resolveTicker} and the surrounding {@code chat} response for every input
 * where the resolve bug condition does NOT hold — i.e. any <em>non-suffixed</em> input:
 * plain &le;5-letter tokens (with or without a leading {@code $}), stop-word-only / ticker-free
 * messages, ambiguous multi-ticker messages, and resolved tickers that have no Redis price.
 *
 * <p>Per the observation-first methodology, the assertions below were derived by observing the
 * UNFIXED {@code ChatController}. They MUST PASS on the unfixed code (capturing the baseline) and
 * must continue to pass after the Root Cause 2 resolver fix is applied (proving no regression).
 *
 * <p><b>Observed baseline outcomes encoded here:</b>
 * <ul>
 *   <li>"How is {@code AAPL} doing?" with {@code AAPL} tracked → resolves to {@code AAPL} and
 *       returns its summary ("Here's what I found for AAPL …") — req 3.1.</li>
 *   <li>"${@code MSFT} outlook" with {@code MSFT} tracked → resolves to {@code MSFT} — req 3.1.</li>
 *   <li>A stop-word-only / ticker-free message (e.g. "IS THE DO") → clarification response
 *       "I couldn't identify a single ticker symbol from your message" — req 3.2, 3.3.</li>
 *   <li>An ambiguous two-ticker message (e.g. "Compare AAPL and MSFT") → the same clarification
 *       response (candidate ordering / single-candidate fallback preserved) — req 3.2.</li>
 *   <li>A resolved plain ticker whose summary has a {@code null} price → no-data response
 *       "I don't have any data for {ticker} right now…" — req 3.4.</li>
 * </ul>
 *
 * <p>Mocks are constructed per-iteration (rather than via {@code MockitoExtension}) so the jqwik
 * {@code @Property} lifecycle stays clean and each generated input is fully isolated. The MockMvc
 * standalone setup mirrors {@link ChatControllerTest}.
 */
class ChatControllerPreservationPropertyTest {

    private static final String CLARIFICATION_FRAGMENT =
            "couldn't identify a single ticker symbol";
    private static final String NO_DATA_FRAGMENT = "don't have any data for";
    private static final String OK_FRAGMENT = "Here's what I found for";

    private static final String[] STOP_WORDS =
            ChatController.STOP_WORDS.toArray(new String[0]);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── req 3.1 — plain uppercase tracked symbol resolves and returns its summary ─────────────

    /**
     * Observed baseline: a message of the form "How is {SYM} doing?" where {SYM} is a plain
     * 1–5 uppercase-letter tracked symbol resolves to {SYM} and produces the "ok" summary
     * response containing the symbol and its latest price.
     *
     * <p><b>Validates: Requirements 3.1</b>
     */
    @Property(tries = 100)
    void plainUppercaseTrackedSymbol_resolvesAndReturnsSummary(
            @ForAll("plainSymbols") String symbol) throws Exception {

        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);
        TickerSummary summary = new TickerSummary(symbol,
                new BigDecimal("123.45"),
                List.of(new BigDecimal("123.45")), null, null);
        when(mds.getTickerSummary(symbol)).thenReturn(summary);
        when(ai.getSentiment(symbol)).thenReturn(symbol + " is Neutral.");

        String response = chat(mds, ai, "How is " + symbol + " doing?", null);

        assertThat(response).contains(OK_FRAGMENT + " " + symbol);
        assertThat(response).contains("123.45");
    }

    // ── req 3.1 — $-prefixed tracked symbol resolves and returns its summary ──────────────────

    /**
     * Observed baseline: a message of the form "${SYM} outlook" where {SYM} is a plain
     * 1–5 letter tracked symbol resolves to {SYM} via the dollar-pattern source.
     *
     * <p><b>Validates: Requirements 3.1</b>
     */
    @Property(tries = 100)
    void dollarPrefixedTrackedSymbol_resolvesAndReturnsSummary(
            @ForAll("plainSymbols") String symbol) throws Exception {

        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);
        TickerSummary summary = new TickerSummary(symbol,
                new BigDecimal("420.00"),
                List.of(new BigDecimal("420.00")), null, null);
        when(mds.getTickerSummary(symbol)).thenReturn(summary);
        when(ai.getSentiment(symbol)).thenReturn(symbol + " is Neutral.");

        String response = chat(mds, ai, "$" + symbol + " outlook", null);

        assertThat(response).contains(OK_FRAGMENT + " " + symbol);
        assertThat(response).contains("420");
    }

    // ── req 3.2, 3.3 — stop-word-only / ticker-free message returns clarification ─────────────

    /**
     * Observed baseline: a message composed solely of common English stop words that match the
     * uppercase pattern (e.g. "IS THE DO") yields no ticker candidate and returns the
     * clarification response. Confirms stop-word exclusion (req 3.3) and the no-ticker
     * clarification path (req 3.2).
     *
     * <p><b>Validates: Requirements 3.2, 3.3</b>
     */
    @Property(tries = 100)
    void stopWordOnlyMessage_returnsClarification(
            @ForAll("stopWordMessages") String message) throws Exception {

        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);
        // No ticker is tracked: even if a token slipped through it would not resolve.
        when(mds.getTickerSummary(anyString())).thenReturn(null);

        String response = chat(mds, ai, message, null);

        assertThat(response).contains(CLARIFICATION_FRAGMENT);
    }

    // ── req 3.2 — ambiguous multi-ticker message returns clarification ────────────────────────

    /**
     * Observed baseline: a message referencing two distinct untracked plain symbols
     * (e.g. "Compare AAPL and MSFT") is ambiguous — neither resolves and the single-candidate
     * fallback does not fire (two candidates) — so it returns the clarification response.
     * Confirms candidate ordering / fallback behavior is preserved.
     *
     * <p><b>Validates: Requirements 3.2</b>
     */
    @Property(tries = 100)
    void ambiguousTwoTickerMessage_returnsClarification(
            @ForAll("twoDistinctSymbols") List<String> symbols) throws Exception {

        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);
        // Neither symbol is tracked (default mock returns null) → ambiguous, not resolvable.
        when(mds.getTickerSummary(anyString())).thenReturn(null);

        String message = "Compare " + symbols.get(0) + " and " + symbols.get(1);
        String response = chat(mds, ai, message, null);

        assertThat(response).contains(CLARIFICATION_FRAGMENT);
    }

    // ── req 3.4 — resolved plain ticker with no Redis price returns no-data ───────────────────

    /**
     * Observed baseline: a message referencing a single plain symbol whose summary has a
     * {@code null} latest price (no Redis data) resolves to that symbol via the fallback and
     * returns the no-data response "I don't have any data for {ticker} right now…".
     *
     * <p><b>Validates: Requirements 3.4</b>
     */
    @Property(tries = 100)
    void resolvedPlainSymbolWithNullPrice_returnsNoData(
            @ForAll("plainSymbols") String symbol) throws Exception {

        MarketDataService mds = mock(MarketDataService.class);
        AiInsightService ai = mock(AiInsightService.class);
        // Summary exists but has no price → not "tracked", resolves via fallback, then no-data.
        TickerSummary noPrice = new TickerSummary(symbol, null, Collections.emptyList(), null, null);
        when(mds.getTickerSummary(symbol)).thenReturn(noPrice);

        String response = chat(mds, ai, "What about " + symbol + "?", null);

        assertThat(response).contains(NO_DATA_FRAGMENT + " " + symbol);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a fresh MockMvc standalone setup (mirroring {@link ChatControllerTest}) for the given
     * mocks, performs the chat request, and returns the raw response body.
     */
    private String chat(MarketDataService mds, AiInsightService ai,
                        String message, String ticker) throws Exception {
        ChatController controller = new ChatController(mds, ai);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String body = objectMapper.writeValueAsString(new ChatRequest(message, ticker));
        return mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Plain ticker symbols: 1–5 uppercase letters that are NOT stop words. This is exactly the
     * shape the unfixed resolver recognizes (the non-bug input space for req 3.1 / 3.4).
     */
    @Provide
    Arbitrary<String> plainSymbols() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(5)
                .filter(s -> !ChatController.STOP_WORDS.contains(s));
    }

    /**
     * Messages composed of 1–5 stop words joined by spaces (e.g. "IS THE DO"). Every token is a
     * stop word, so the resolver produces zero candidates → clarification.
     */
    @Provide
    Arbitrary<String> stopWordMessages() {
        return Arbitraries.of(STOP_WORDS)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .map(words -> String.join(" ", words));
    }

    /**
     * Two distinct plain symbols for the ambiguous-message case.
     */
    @Provide
    Arbitrary<List<String>> twoDistinctSymbols() {
        return plainSymbols().set().ofSize(2).map(List::copyOf);
    }
}
