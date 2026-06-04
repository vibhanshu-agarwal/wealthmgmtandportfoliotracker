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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link ChatController} (Task 10 — wired to {@link ChatResolutionService}).
 *
 * <p>The controller now delegates entirely to {@code ChatResolutionService}; these tests verify
 * the HTTP contract and delegation without invoking any real LLM or Redis (Req 9.3).
 * Detailed resolution behavior is tested in {@link ChatResolutionServiceTest} and
 * {@link ChatControllerSliceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

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
    void chat_returnsOkWithServiceResponse() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "Here's what I found for Apple (AAPL): the latest price is USD 178.50."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is Apple doing?", "ticker": "AAPL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("178.50")));
    }

    @Test
    void chat_withNoTicker_delegatesToService() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("I couldn't identify a specific asset."));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is the market doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("identify")));

        verify(chatResolutionService).handle(any(ChatRequest.class));
    }

    @Test
    void chat_comparisonQuery_delegatesToService() throws Exception {
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(
                        "I can summarize one asset at a time — Apple (AAPL) or Microsoft (MSFT)?"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "Compare AAPL and MSFT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("MSFT")));
    }

    @Test
    void chat_serviceResponse_preservesEndpointContract() throws Exception {
        // Verifies the endpoint still works with POST /api/chat + ChatRequest/ChatResponse contract
        when(chatResolutionService.handle(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("some response"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"test\", \"ticker\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").exists());
    }
}
