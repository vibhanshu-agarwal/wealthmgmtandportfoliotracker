package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
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
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    private MockMvc mockMvc;

    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController(marketDataService, aiInsightService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void chat_withExplicitTicker_returnsConversationalResponse() throws Exception {
        TickerSummary summary = new TickerSummary("AAPL",
                new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50"), new BigDecimal("177.20")),
                new BigDecimal("0.73"), null);
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsightService.getSentiment("AAPL")).thenReturn("AAPL is Bullish.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is Apple doing?", "ticker": "AAPL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("AAPL")))
                .andExpect(jsonPath("$.response", containsString("178.5")));
    }

    @Test
    void chat_withTickerInMessage_extractsAndResponds() throws Exception {
        TickerSummary summary = new TickerSummary("MSFT",
                new BigDecimal("420.00"),
                List.of(new BigDecimal("420.00")), null, null);
        when(marketDataService.getTickerSummary("MSFT")).thenReturn(summary);
        when(aiInsightService.getSentiment("MSFT")).thenReturn("MSFT is Neutral.");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is MSFT doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("MSFT")))
                .andExpect(jsonPath("$.response", containsString("420")));
    }

    @Test
    void chat_withNoIdentifiableTicker_returnsPromptToSpecify() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "How is the market doing?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("specify")));
    }

    @Test
    void chat_withUnknownTicker_returnsNoDataMessage() throws Exception {
        TickerSummary empty = new TickerSummary("ZZZZ", null, Collections.emptyList(), null, null);
        when(marketDataService.getTickerSummary("ZZZZ")).thenReturn(empty);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "What about this?", "ticker": "ZZZZ"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("don't have any data")));
    }

    @Test
    void chat_withAiFailure_returnsResponseWithUnavailableNote() throws Exception {
        TickerSummary summary = new TickerSummary("GOOG",
                new BigDecimal("175.00"),
                List.of(new BigDecimal("175.00")), null, null);
        when(marketDataService.getTickerSummary("GOOG")).thenReturn(summary);
        when(aiInsightService.getSentiment("GOOG"))
                .thenThrow(new AdvisorUnavailableException("timeout"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "What about GOOG?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", containsString("GOOG")))
                .andExpect(jsonPath("$.response", containsString("temporarily unavailable")));
    }
}
