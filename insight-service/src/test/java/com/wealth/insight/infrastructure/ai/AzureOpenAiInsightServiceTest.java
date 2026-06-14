package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.dto.TickerSummary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AzureOpenAiInsightService} that mock the Spring AI {@link ChatClient}
 * fluent chain. Exercises the real Azure adapter code path without calling Azure OpenAI.
 */
class AzureOpenAiInsightServiceTest {

    private static final String TICKER = "AAPL";
    private static final TickerSummary SUMMARY = new TickerSummary(
            TICKER,
            new BigDecimal("180.00"),
            List.of(new BigDecimal("180.00"), new BigDecimal("175.00"), new BigDecimal("172.50")),
            new BigDecimal("4.35"),
            null
    );

    @Test
    void getSentiment_happyPath_returnsChatClientResponse() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("AAPL is Bullish. Strong uptrend over last 3 sessions.");

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary(TICKER)).thenReturn(SUMMARY);

        AzureOpenAiInsightService service = new AzureOpenAiInsightService(builder, marketData);

        String result = service.getSentiment(TICKER);

        assertThat(result).isEqualTo("AAPL is Bullish. Strong uptrend over last 3 sessions.");
        verify(marketData).getTickerSummary(TICKER);
    }

    @Test
    void getSentiment_blankResponse_throwsAdvisorUnavailable() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("   ");

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary(TICKER)).thenReturn(SUMMARY);

        AzureOpenAiInsightService service = new AzureOpenAiInsightService(builder, marketData);

        assertThatThrownBy(() -> service.getSentiment(TICKER))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessageContaining("empty response")
                .hasMessageContaining(TICKER);
    }

    @Test
    void getSentiment_nullResponse_throwsAdvisorUnavailable() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(null);

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary(TICKER)).thenReturn(SUMMARY);

        AzureOpenAiInsightService service = new AzureOpenAiInsightService(builder, marketData);

        assertThatThrownBy(() -> service.getSentiment(TICKER))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void getSentiment_chatClientThrows_wrappedAsAdvisorUnavailable() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("401 Unauthorized"));

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary(TICKER)).thenReturn(SUMMARY);

        AzureOpenAiInsightService service = new AzureOpenAiInsightService(builder, marketData);

        assertThatThrownBy(() -> service.getSentiment(TICKER))
                .isInstanceOf(AdvisorUnavailableException.class)
                .hasMessageContaining("Azure OpenAI unavailable")
                .hasMessageContaining(TICKER)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void getSentiment_advisorUnavailable_propagatedWithoutRewrapping() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        AdvisorUnavailableException inner = new AdvisorUnavailableException("upstream failure");
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(inner);

        MarketDataService marketData = mock(MarketDataService.class);
        when(marketData.getTickerSummary(TICKER)).thenReturn(SUMMARY);

        AzureOpenAiInsightService service = new AzureOpenAiInsightService(builder, marketData);

        assertThatThrownBy(() -> service.getSentiment(TICKER))
                .isSameAs(inner);
    }
}
