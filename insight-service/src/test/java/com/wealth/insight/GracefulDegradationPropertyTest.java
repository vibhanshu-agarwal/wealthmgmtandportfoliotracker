package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.dto.TickerSummary;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Property 6: Graceful degradation preserves price data on AI failure.
 *
 * <p>For any TickerSummary with valid price data, when AiInsightService throws
 * AdvisorUnavailableException, the response still contains original price fields
 * with aiSummary=null and HTTP 200.
 */
@ExtendWith(MockitoExtension.class)
class GracefulDegradationPropertyTest {

    @Mock private InsightService insightService;
    @Mock private MarketDataService marketDataService;
    @Mock private AiInsightService aiInsightService;

    @RepeatedTest(100)
    void marketSummary_aiFailure_preservesPriceDataAndReturns200() throws Exception {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomTicker(rng);
        BigDecimal price = BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0));
        List<BigDecimal> history = randomPriceHistory(rng);
        BigDecimal trend = history.size() >= 2
                ? BigDecimal.valueOf(rng.nextDouble(-50, 50)).setScale(2, java.math.RoundingMode.HALF_UP)
                : null;

        TickerSummary original = new TickerSummary(ticker, price, history, trend, null);
        Map<String, TickerSummary> raw = new LinkedHashMap<>();
        raw.put(ticker, original);

        when(marketDataService.getMarketSummary()).thenReturn(raw);
        when(aiInsightService.getSentiment(anyString()))
                .thenThrow(new AdvisorUnavailableException("test failure"));

        InsightController controller = new InsightController(insightService, marketDataService, aiInsightService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(get("/api/insights/market-summary"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(ticker);
        assertThat(body).contains(price.toPlainString());
        // aiSummary should be null (absent from JSON)
        assertThat(body).doesNotContain("\"aiSummary\":\"");
    }

    private static String randomTicker(ThreadLocalRandom rng) {
        String[] tickers = {"AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "NVDA"};
        return tickers[rng.nextInt(tickers.length)];
    }

    private static List<BigDecimal> randomPriceHistory(ThreadLocalRandom rng) {
        int size = rng.nextInt(1, 11);
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            prices.add(BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0)));
        }
        return prices;
    }
}
