package com.wealth.insight;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property 1: Sliding window never exceeds configured size.
 *
 * <p>For any sequence of PriceUpdatedEvents for the same ticker,
 * the LTRIM call always trims to [0, WINDOW_SIZE-1].
 */
class SlidingWindowPropertyTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ListOperations<String, String> listOps;
    private ZSetOperations<String, String> zSetOps;
    private MarketDataService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        listOps = mock(ListOperations.class);
        zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        service = new MarketDataService(redisTemplate);
    }

    @RepeatedTest(100)
    void processUpdate_anySequence_trimsCappedAtWindowSize() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomTicker(rng);
        int eventCount = rng.nextInt(1, 51);

        for (int i = 0; i < eventCount; i++) {
            BigDecimal price = BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0));
            PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, price);
            service.processUpdate(event);
        }

        // Verify every processUpdate call trimmed to [0, WINDOW_SIZE - 1]
        String historyKey = MarketDataService.HISTORY_KEY_PREFIX + ticker;
        verify(listOps, times(eventCount)).trim(eq(historyKey), eq(0L), eq((long) MarketDataService.WINDOW_SIZE - 1));
    }

    @RepeatedTest(100)
    void processUpdate_anyEvent_pushesNewestAtHead() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomTicker(rng);
        BigDecimal price = BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0));
        PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, price);

        service.processUpdate(event);

        String historyKey = MarketDataService.HISTORY_KEY_PREFIX + ticker;
        verify(listOps).leftPush(eq(historyKey), eq(price.toPlainString()));
    }

    private static String randomTicker(ThreadLocalRandom rng) {
        String[] tickers = {"AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "NVDA", "META", "NFLX"};
        return tickers[rng.nextInt(tickers.length)];
    }
}
