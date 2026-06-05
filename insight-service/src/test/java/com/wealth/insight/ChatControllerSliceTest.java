package com.wealth.insight;

import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@code POST /api/chat} (Task 16 / Req 6.5, 9.2, 9.3).
 *
 * <p>Tests the full HTTP layer end-to-end through {@link ChatController} →
 * {@link ChatResolutionService} with a mocked service. Asserts:
 * <ul>
 *   <li>HTTP 200 for all resolution outcomes (never 4xx/5xx for normal flows).</li>
 *   <li>The endpoint always returns a non-empty assistant response (Property P6).</li>
 *   <li>No real LLM is invoked in tests (Req 9.3) — {@code ChatResolutionService} is mocked.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerSliceTest {

    @Mock private ChatResolutionService chatResolutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatResolutionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void chat_resolvedOutcome_returns200WithResponse() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "Here's what I found for Apple (AAPL): the latest price is USD 178.50."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Tell me about Apple"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("178.50")));
    }

    @Test
    void chat_clarificationOutcome_returns200WithClarificationText() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "I couldn't identify a specific asset from your message. "
                        + "Could you be more specific?"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "how is the market?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("specific")));
    }

    @Test
    void chat_comparisonRedirectOutcome_returns200WithBothAssetNames() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "I can summarize one asset at a time for now. "
                        + "Which would you like — Apple (AAPL) or Microsoft (MSFT)?"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "compare AAPL and MSFT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("Apple (AAPL)")))
                .andExpect(jsonPath("$.response", containsString("Microsoft (MSFT)")));
    }

    @Test
    void chat_discoveryOutcome_returns200WithGroupedListing() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "Here are some of the assets I'm currently tracking:\n"
                        + "**CRYPTO**: Bitcoin (BTC-USD), Ethereum (ETH-USD)\n"
                        + "...and more."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "what assets do you track?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("CRYPTO")))
                .andExpect(jsonPath("$.response", containsString("Bitcoin")));
    }

    @Test
    void chat_greetingHelpOutcome_returns200WithCapabilityOverview() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "Hello! I'm your market insight assistant. "
                        + "I can help you with price and trend data."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("assistant")));
    }

    @Test
    void chat_noDataOutcome_returns200WithNoDataMessage() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "I don't have any live data for AAPL right now. "
                        + "It may not be actively tracked yet."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "AAPL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")));
    }

    // ── P6: Never empty ────────────────────────────────────────────────────────────────────

    @Test
    void chat_serviceReturnsResponse_responseFieldNeverBlank() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("Some non-empty response"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", not("")));

        verify(chatResolutionService).handle(any(ChatRequest.class));
    }

    // ── Req 9.3: No real LLM invoked ─────────────────────────────────────────────────────

    @Test
    void chat_withExplicitTicker_delegatesToService_noLlmInvoked() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("Here's what I found for AAPL: USD 178.50."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "how is apple doing?", "ticker": "AAPL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")));

        // Verify delegation — real LLM is never called (service is fully mocked)
        verify(chatResolutionService).handle(any(ChatRequest.class));
    }
}
