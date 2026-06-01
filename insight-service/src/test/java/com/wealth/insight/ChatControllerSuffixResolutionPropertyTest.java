package com.wealth.insight;

import com.wealth.insight.dto.TickerSummary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Property 2 (Bug Condition) — Suffixed tracked symbols resolve to themselves.
 *
 * <p><b>Validates: Requirements 1.3, 1.4, 1.5, 1.6, 2.3, 2.4, 2.5, 2.6</b>
 *
 * <p>This is a <b>bug condition exploration test</b> written against the UNFIXED code. It encodes the
 * expected (post-fix) behavior, so it is <b>EXPECTED TO FAIL</b> on the current resolver — that failure
 * confirms Root Cause 2 (suffix-blind candidate extraction). It will validate the fix once it passes
 * after {@code ChatController.resolveTicker} learns to recognize suffixed symbols.
 *
 * <h2>Bug Condition (from design)</h2>
 * <pre>
 * isBugCondition_Resolve(X) = isTracked(S) AND hasSuffixFormat(S) AND resolveTicker(X) != S
 *   where hasSuffixFormat(S) matches one of:  -USD | =X | .NS
 * </pre>
 *
 * <h2>Property (Fix Checking)</h2>
 * <pre>
 * FOR ALL X WHERE isBugCondition_Resolve(X) DO
 *   resolved := resolveTicker'(X)
 *   ASSERT resolved = S                                  // exact tracked symbol, suffix intact
 *   ASSERT redisKeyUsed(resolved) = "market:latest:" + S // exact-key lookup
 * END FOR
 * </pre>
 *
 * <h2>Test design</h2>
 * <ul>
 *   <li>Drives {@code ChatController.resolveTicker} through the {@code POST /api/chat} endpoint via the
 *       {@code MockMvc} standalone setup pattern from {@link ChatControllerTest}.</li>
 *   <li>{@link MarketDataService} is stubbed so that <em>only</em> the generated suffixed symbol {@code S}
 *       is tracked (a non-null {@code market:latest:{S}} price). Every other candidate the resolver
 *       might extract returns {@code null} (not tracked).</li>
 *   <li>The generator is scoped to tracked symbols over the suffix alphabet {@code {-USD, =X, .NS}}
 *       (e.g. {@code ROSE-USD}, {@code USDCHF=X}, {@code RELIANCE.NS}) so failing cases are
 *       reproducible.</li>
 *   <li>A successful resolution renders {@code "Here's what I found for {S}: ..."} (see
 *       {@code ChatController.buildConversationalResponse}). Asserting the response contains that marker
 *       proves {@code resolveTicker} returned the exact symbol {@code S}.</li>
 *   <li>{@link MarketDataService#getTickerSummary} composes the Redis key as
 *       {@code LATEST_KEY_PREFIX + ticker} = {@code "market:latest:" + S}. Verifying it was invoked with
 *       the exact symbol {@code S} is therefore equivalent to asserting the Redis lookup key equals
 *       {@code market:latest:{S}} (req 1.6 / 2.6).</li>
 * </ul>
 */
class ChatControllerSuffixResolutionPropertyTest {

    private static final BigDecimal TRACKED_PRICE = new BigDecimal("123.45");

    /**
     * Property 2: For any tracked suffixed symbol {@code S} (suffix {@code -USD} / {@code =X} / {@code .NS}),
     * resolving a chat message that references {@code S} must yield the full symbol {@code S} (suffix
     * intact) and look it up by the exact Redis key {@code market:latest:{S}}.
     *
     * <p><b>Validates: Requirements 1.3, 1.4, 1.5, 1.6, 2.3, 2.4, 2.5, 2.6</b>
     *
     * <p><b>EXPECTED OUTCOME on UNFIXED code: FAIL</b> — the resolver strips suffix punctuation and rejects
     * tokens longer than 5 letters, so {@code S} is never offered to {@code findFirstTrackedTicker} and the
     * {@code market:latest:{S}} lookup never happens. The response is a clarification or no-data message
     * instead of the {@code "Here's what I found for {S}"} marker.
     */
    @Property(tries = 100)
    void p2_suffixedTrackedSymbol_resolvesToExactSymbolAndExactRedisKey(
            @ForAll("trackedSuffixedSymbols") String symbol) throws Exception {

        // Arrange: stub MarketDataService so ONLY `symbol` is tracked (non-null market:latest:{symbol}).
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiInsightService aiInsightService = mock(AiInsightService.class);

        TickerSummary trackedSummary = new TickerSummary(
                symbol, TRACKED_PRICE, List.of(TRACKED_PRICE), null, null);
        when(marketDataService.getTickerSummary(symbol)).thenReturn(trackedSummary);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(marketDataService, aiInsightService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // A chat message that references the tracked suffixed symbol as a whitespace-delimited token.
        String body = "{\"message\": \"How is " + symbol + " doing?\"}";

        // Act
        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        String response = result.getResponse().getContentAsString();

        // Assert (Property 2): the message resolves to the exact tracked symbol S (suffix intact).
        assertThat(response)
                .as("isBugCondition_Resolve(%s): a tracked suffixed symbol must resolve to itself and "
                        + "return its market summary, but the resolver produced: %s", symbol, response)
                .contains("Here's what I found for " + symbol);

        // Assert (req 1.6 / 2.6): the lookup uses the exact tracked symbol as the Redis key
        // (MarketDataService composes market:latest:{S} = LATEST_KEY_PREFIX + S).
        verify(marketDataService, atLeastOnce()).getTickerSummary(symbol);
    }

    /**
     * Tracked symbols scoped to the registry suffix alphabet {@code {-USD, =X, .NS}}:
     * <ul>
     *   <li>crypto: 2–5 uppercase letters + {@code -USD}  (e.g. {@code ROSE-USD}, {@code BTC-USD})</li>
     *   <li>forex:  6 uppercase letters + {@code =X}      (e.g. {@code USDCHF=X}, {@code NZDUSD=X})</li>
     *   <li>NSE:    2–10 uppercase letters + {@code .NS}  (e.g. {@code RELIANCE.NS}, {@code TCS.NS})</li>
     * </ul>
     */
    @Provide
    Arbitrary<String> trackedSuffixedSymbols() {
        Arbitrary<String> crypto = upperLetters(2, 5).map(stem -> stem + "-USD");
        Arbitrary<String> forex = upperLetters(6, 6).map(stem -> stem + "=X");
        Arbitrary<String> nse = upperLetters(2, 10).map(stem -> stem + ".NS");
        return Arbitraries.oneOf(crypto, forex, nse);
    }

    private static Arbitrary<String> upperLetters(int minLength, int maxLength) {
        return Arbitraries.strings()
                .withChars('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
                        'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z')
                .ofMinLength(minLength)
                .ofMaxLength(maxLength);
    }
}
