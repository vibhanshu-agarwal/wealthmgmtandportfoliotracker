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
import static org.mockito.Mockito.lenient;
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
 * <p><b>Precedence test design:</b> each precedence test stubs BOTH the suffixed symbol AND the
 * competing plain uppercase candidate as tracked (non-null price). This is the critical condition
 * that makes the test a genuine regression guard — without a tracked plain stem, the suffix would
 * win by default even on the old buggy ordering, and the test would not detect a regression.
 * {@code lenient()} stubbing is used for the plain stem because the fix causes the suffix path to
 * win before the uppercase path is checked, so the plain stub is set up to create the competition
 * but may not be invoked.
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
     * <p>Before the fix, {@code regex-uppercase-tracked} was evaluated first and would return
     * {@code ABC} (the plain stem extracted by the uppercase pattern) before the suffix path
     * was checked. After the fix, {@code suffix-aware-tracked} is evaluated first.
     *
     * <p>{@code ABC} is stubbed as tracked via {@code lenient()} because the fix causes the
     * suffix path to win before the uppercase path is reached — so the stub creates the real
     * competition without triggering Mockito's unnecessary-stubbing check.
     */
    @Test
    void resolveTicker_suffixedSymbolWinsOverPlainStem_whenBothTracked() throws Exception {
        TickerSummary plainSummary = new TickerSummary("ABC",
                new BigDecimal("10.00"), List.of(new BigDecimal("10.00")), null, null);
        TickerSummary suffixedSummary = new TickerSummary("ABC-USD",
                new BigDecimal("99.99"), List.of(new BigDecimal("99.99")), null, null);

        // Both are tracked. lenient() on the plain stem: it must be set up to create the
        // competition, but the fix means it is never invoked (suffix wins first).
        lenient().when(marketDataService.getTickerSummary("ABC")).thenReturn(plainSummary);
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

        // The suffixed Redis key must have been used.
        verify(marketDataService, atLeastOnce()).getTickerSummary("ABC-USD");
        // The plain stem must NOT have won — if it had, the response would contain "10.00".
        verify(marketDataService, never()).getTickerSummary("ABC");
    }

    /**
     * Forex {@code =X} precedence: {@code USDCHF=X} (suffixed, tracked) must win over
     * {@code X} (the single uppercase token the pattern extracts from {@code USDCHF=X},
     * also tracked).
     *
     * <p>Without the fix, {@code regex-uppercase-tracked} would return {@code X} first.
     * {@code X} is stubbed via {@code lenient()} for the same reason as {@code ABC} above.
     */
    @Test
    void resolveTicker_forexSuffixWinsOverPlainStem_whenBothTracked() throws Exception {
        // X is the uppercase token extracted from "USDCHF=X" (USDCHF is 6 chars, exceeds cap).
        TickerSummary xSummary = new TickerSummary("X",
                new BigDecimal("1.00"), List.of(new BigDecimal("1.00")), null, null);
        TickerSummary suffixedSummary = new TickerSummary("USDCHF=X",
                new BigDecimal("0.9050"), List.of(new BigDecimal("0.9050")), null, null);

        lenient().when(marketDataService.getTickerSummary("X")).thenReturn(xSummary);
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

        verify(marketDataService, atLeastOnce()).getTickerSummary("USDCHF=X");
        verify(marketDataService, never()).getTickerSummary("X");
    }

    /**
     * NSE {@code .NS} precedence: {@code RELIANCE.NS} (suffixed, tracked) must win over
     * {@code NS} (the uppercase token extracted from {@code RELIANCE.NS}, also tracked).
     *
     * <p>{@code RELIANCE} is 8 chars and does not match the {@code {1,5}} uppercase cap,
     * so {@code NS} is the only competing uppercase candidate.
     */
    @Test
    void resolveTicker_nseSuffixWinsOverPlainStem_whenBothTracked() throws Exception {
        // NS is the uppercase token extracted from "RELIANCE.NS" (RELIANCE is 8 chars).
        TickerSummary nsSummary = new TickerSummary("NS",
                new BigDecimal("5.00"), List.of(new BigDecimal("5.00")), null, null);
        TickerSummary suffixedSummary = new TickerSummary("RELIANCE.NS",
                new BigDecimal("2950.00"), List.of(new BigDecimal("2950.00")), null, null);

        lenient().when(marketDataService.getTickerSummary("NS")).thenReturn(nsSummary);
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

        verify(marketDataService, atLeastOnce()).getTickerSummary("RELIANCE.NS");
        verify(marketDataService, never()).getTickerSummary("NS");
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
     * <p>{@code NZDUSD=X} is used: the uppercase pattern extracts only {@code X} (1 char).
     * Two additional plain uppercase tokens ({@code PAIR} and {@code FOREX}) are included so
     * there are multiple uppercase candidates and the single-candidate fallback does not fire.
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
     * A second plain uppercase token ({@code STOCK}) is added so there are two uppercase
     * candidates and the single-candidate fallback does not fire.
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
