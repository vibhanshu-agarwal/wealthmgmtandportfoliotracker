package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InsightControllerTest {

    private MockMvc mockMvc;

    @Mock private InsightService insightService;
    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    @BeforeEach
    void setUp() {
        InsightController controller = new InsightController(
                insightService, marketDataService, aiInsightService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- Per-ticker endpoint tests (Task 11.1) ---

    @Test
    void getTickerSummary_knownTicker_returns200WithCorrectJson() throws Exception {
        TickerSummary summary = new TickerSummary("AAPL",
                new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50"), new BigDecimal("177.20")),
                new BigDecimal("0.73"), null);
        when(marketDataService.getTickerSummary("AAPL")).thenReturn(summary);
        when(aiInsightService.getSentiment("AAPL")).thenReturn("AAPL is Bullish. Prices are rising.");

        mockMvc.perform(get("/api/insights/market-summary/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.latestPrice").value(178.50))
                .andExpect(jsonPath("$.priceHistory", hasSize(2)))
                .andExpect(jsonPath("$.trendPercent").value(0.73))
                .andExpect(jsonPath("$.aiSummary").value("AAPL is Bullish. Prices are rising."));
    }

    @Test
    void getTickerSummary_unknownTicker_returns404() throws Exception {
        TickerSummary empty = new TickerSummary("ZZZZ", null, Collections.emptyList(), null, null);
        when(marketDataService.getTickerSummary("ZZZZ")).thenReturn(empty);

        mockMvc.perform(get("/api/insights/market-summary/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Ticker not found"));
    }

    // --- AI enrichment tests (Task 11.3) ---

    @Test
    void getMarketSummary_withAiAvailable_populatesAiSummary() throws Exception {
        Map<String, TickerSummary> raw = new LinkedHashMap<>();
        raw.put("AAPL", new TickerSummary("AAPL", new BigDecimal("178.50"),
                List.of(new BigDecimal("178.50")), null, null));
        when(marketDataService.getMarketSummary()).thenReturn(raw);
        when(aiInsightService.getSentiment("AAPL")).thenReturn("AAPL is Neutral.");

        mockMvc.perform(get("/api/insights/market-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.AAPL.aiSummary").value("AAPL is Neutral."));
    }

    @Test
    void getMarketSummary_withAiFailure_returnsNullAiSummaryAndPriceDataIntact() throws Exception {
        Map<String, TickerSummary> raw = new LinkedHashMap<>();
        raw.put("MSFT", new TickerSummary("MSFT", new BigDecimal("420.00"),
                List.of(new BigDecimal("420.00"), new BigDecimal("418.00")),
                new BigDecimal("0.48"), null));
        when(marketDataService.getMarketSummary()).thenReturn(raw);
        when(aiInsightService.getSentiment("MSFT"))
                .thenThrow(new AdvisorUnavailableException("LLM down"));

        mockMvc.perform(get("/api/insights/market-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MSFT.ticker").value("MSFT"))
                .andExpect(jsonPath("$.MSFT.latestPrice").value(420.00))
                .andExpect(jsonPath("$.MSFT.priceHistory", hasSize(2)))
                .andExpect(jsonPath("$.MSFT.trendPercent").value(0.48))
                .andExpect(jsonPath("$.MSFT.aiSummary").doesNotExist());
    }

    @Test
    void getTickerSummary_withAiFailure_returnsNullAiSummaryAndPriceDataIntact() throws Exception {
        TickerSummary summary = new TickerSummary("GOOG",
                new BigDecimal("175.00"),
                List.of(new BigDecimal("175.00")), null, null);
        when(marketDataService.getTickerSummary("GOOG")).thenReturn(summary);
        when(aiInsightService.getSentiment("GOOG"))
                .thenThrow(new AdvisorUnavailableException("timeout"));

        mockMvc.perform(get("/api/insights/market-summary/GOOG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("GOOG"))
                .andExpect(jsonPath("$.latestPrice").value(175.00))
                .andExpect(jsonPath("$.aiSummary").doesNotExist());
    }
}
