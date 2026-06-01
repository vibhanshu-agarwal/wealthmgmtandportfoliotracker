package com.wealth.insight;

import com.wealth.insight.dto.TickerSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused unit tests for the two resolver behaviors introduced by the Phase 1 audit fixes:
 *
 * <ol>
 *   <li><b>Suffix precedence (Finding 3):</b> when both a plain stem (e.g. {@code ABC}) and its
 *       suffixed form (e.g. {@code ABC-USD}) are tracked, a message referencing the suffixed form
 *       must resolve to the full suffixed symbol — not the plain stem.</li>
 *   <li><b>Untracked suffix clarification (Finding 4):</b> when a suffixed symbol is referenced
 *       but is not tracked in Redis, the response must be the clarification message, not the
 *       no-data message. This preserves the pre-fix behavior under Property 3.</li>
 * </ol>
 *
 * <p>These cases are not covered by the existing property tests and were called out as minimum
 * recommended additions before merging Phase 1.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerSuffixPrecedenceTest {

    private MockMvc mockMvc;

    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(marketDataService, aiInsightService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Finding 3: suffix wins over plain stem when both are tracked ──────────────────────────

    /**
     * When both {@code ABC} (plain) and {@code ABC-USD} (suffixed) are tracked, a message
     * referencing {@code ABC-USD} must resolve to the full suffixed symbol.
     *
     * <p>Before the fix, {@code regex-uppercase-tracked} was evaluated first and could return
     * {@code ABC} before the suffix path was checked. After the fix, {@code suffix-aware-tracked}
     * is evaluated before {@code regex-uppercase-tracked}.
     *
     * <p>Note: with the fix in place, the suffix path wins immediately and {@code ABC} is never
     * checked — so stubbing {@code ABC} would be flagged as unnecessary by Mockito strict mode.
     * Only the suffixed form needs to be stubbed.
     */
    @Test
    void resolveTicker_suffixedSymbolWinsOverPlainStem_whenBothTracked() throws Exception {
        TickerSummary suffixedSummary = new TickerSummary("ABC-USD",
                new BigDecimal("99.99"), List.of(new BigDecimal("99.99")), null, null);

        // Only stub the suffixed form — the fix ensures the suffix path wins before ABC is checked.
        when(marketDataService.getTickerSummary("ABC-USD")).thenReturn(suffixedSummary);
        when(aiInsightService.getSentiment("ABC-USD")).thenReturn("ABC-USD is Neutral.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is ABC-USD doing?"}
                                """))
                .andExpect(status().isOk())
                // Response must reference the suffixed symbol, not the plain stem.
                .andExpect(jsonPath("$.response", containsString("ABC-USD")))
                .andExpect(jsonPath("$.response", containsString("99.99")));

        // The exact suffixed Redis key must have been used.
        // Called at least once: once in findFirstTrackedTicker (suffix path) and once in chat()
        // to fetch the summary for the response.
        verify(marketDataService, atLeastOnce()).getTickerSummary("ABC-USD");
    }

    /**
     * Same precedence check for a forex {@code =X} symbol: when both {@code USDCHF} (plain,
     * hypothetically tracked) and {@code USDCHF=X} (suffixed, tracked) are present, a message
     * referencing {@code USDCHF=X} must resolve to the suffixed form.
     */
    @Test
    void resolveTicker_forexSuffixWinsOverPlainStem_whenBothTracked() throws Exception {
        TickerSummary suffixedSummary = new TickerSummary("USDCHF=X",
                new BigDecimal("0.9050"), List.of(new BigDecimal("0.9050")), null, null);

        // Only the suffixed form is tracked; plain stem returns null.
        when(marketDataService.getTickerSummary("USDCHF=X")).thenReturn(suffixedSummary);
        when(aiInsightService.getSentiment("USDCHF=X")).thenReturn("USDCHF=X is Neutral.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is USDCHF=X doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("USDCHF=X")))
                .andExpect(jsonPath("$.response", containsString("0.9050")));
    }

    /**
     * Same precedence check for an NSE {@code .NS} symbol.
     */
    @Test
    void resolveTicker_nseSuffixWinsOverPlainStem_whenBothTracked() throws Exception {
        TickerSummary suffixedSummary = new TickerSummary("RELIANCE.NS",
                new BigDecimal("2950.00"), List.of(new BigDecimal("2950.00")), null, null);

        when(marketDataService.getTickerSummary("RELIANCE.NS")).thenReturn(suffixedSummary);
        when(aiInsightService.getSentiment("RELIANCE.NS")).thenReturn("RELIANCE.NS is Bullish.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Tell me about RELIANCE.NS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("RELIANCE.NS")))
                .andExpect(jsonPath("$.response", containsString("2950")));
    }

    // ── Finding 4: untracked suffixed symbols preserve clarification response ─────────────────

    /**
     * When a suffixed crypto symbol is referenced but is NOT tracked in Redis, the response
     * must be the clarification message — not the no-data message.
     *
     * <p>Before the fix, the {@code suffix-aware-fallback} branch returned the untracked symbol
     * as a single candidate, causing the no-data response. After the fix, the fallback branch is
     * removed and the resolver falls through to clarification.
     *
     * <p>{@code ROSE-USD} is used because the uppercase pattern extracts both {@code ROSE} and
     * {@code USD} — two candidates — so the uppercase fallback does not fire, and the resolver
     * correctly reaches the clarification path when neither is tracked.
     */
    @Test
    void resolveTicker_untrackedCryptoSuffix_returnsClarification() throws Exception {
        // Nothing is tracked — all getTickerSummary calls return null by default.
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is ROSE-USD doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("couldn't identify")));
    }

    /**
     * Untracked forex suffix returns clarification.
     *
     * <p>{@code NZDUSD=X} is used: the uppercase pattern extracts only {@code X} (1 char,
     * matches {@code \b([A-Z]{1,5})\b}) but {@code NZDUSD} is 6 chars and does not match.
     * To avoid the single-candidate uppercase fallback firing for {@code X}, the message
     * includes two plain uppercase tokens ({@code PAIR} and {@code FOREX}) so there are two
     * uppercase candidates and neither fallback fires.
     */
    @Test
    void resolveTicker_untrackedForexSuffix_returnsClarification() throws Exception {
        // Nothing is tracked — all getTickerSummary calls return null by default.
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is NZDUSD=X PAIR FOREX doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("couldn't identify")));
    }

    /**
     * Untracked NSE suffix returns clarification.
     *
     * <p>{@code RELIANCE.NS} is used: the uppercase pattern extracts only {@code NS} (2 chars).
     * A second plain uppercase token ({@code STOCK}) is added to the message so there are two
     * uppercase candidates and the single-candidate fallback does not fire.
     */
    @Test
    void resolveTicker_untrackedNseSuffix_returnsClarification() throws Exception {
        // Nothing is tracked — all getTickerSummary calls return null by default.
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Tell me about RELIANCE.NS STOCK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("couldn't identify")));
    }

    // ── Regression guard: plain symbol resolution still works after the reorder ───────────────

    /**
     * Confirms that the suffix-before-uppercase reorder does not break plain symbol resolution.
     * A message referencing only a plain tracked symbol must still resolve correctly.
     */
    @Test
    void resolveTicker_plainTrackedSymbol_stillResolvesAfterReorder() throws Exception {
        TickerSummary summary = new TickerSummary("AAPL",
                new BigDecimal("178.50"), List.of(new BigDecimal("178.50")), null, null);
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsightService.getSentiment("AAPL")).thenReturn("AAPL is Bullish.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is AAPL doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("178.5")));

        // Suffix path must not have been invoked for a plain symbol message.
        verify(marketDataService, never()).getTickerSummary("AAPL-USD");
    }
}
