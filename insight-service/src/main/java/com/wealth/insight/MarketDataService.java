package com.wealth.insight;

import com.wealth.insight.dto.TickerSummary;
import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Stateful market data aggregation backed by Redis.
 *
 * <p>Maintains two structures per ticker:
 * <ul>
 *   <li>{@code market:latest:{ticker}} — the most recent price (String key-value)</li>
 *   <li>{@code market:history:{ticker}} — a capped list of the last 10 prices (newest at head)</li>
 * </ul>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    static final String LATEST_KEY_PREFIX = "market:latest:";
    static final String HISTORY_KEY_PREFIX = "market:history:";
    static final int WINDOW_SIZE = 10;

    private final StringRedisTemplate redisTemplate;

    public MarketDataService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Processes a price update: stores the latest price and appends to the sliding window.
     */
    public void processUpdate(PriceUpdatedEvent event) {
        String ticker = event.ticker();
        String price = event.newPrice().toPlainString();

        // 1. Update latest price
        redisTemplate.opsForValue().set(LATEST_KEY_PREFIX + ticker, price);

        // 2. Push to history list (newest at head)
        redisTemplate.opsForList().leftPush(HISTORY_KEY_PREFIX + ticker, price);

        // 3. Trim to sliding window size
        redisTemplate.opsForList().trim(HISTORY_KEY_PREFIX + ticker, 0, WINDOW_SIZE - 1);

        log.debug("Stored price update for {}: {}", ticker, price);
    }

    /**
     * Returns a market summary for all tracked tickers.
     */
    public Map<String, TickerSummary> getMarketSummary() {
        Set<String> keys = redisTemplate.keys(LATEST_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, TickerSummary> summaries = new LinkedHashMap<>();
        for (String key : keys) {
            String ticker = key.substring(LATEST_KEY_PREFIX.length());
            summaries.put(ticker, buildTickerSummary(ticker));
        }
        return summaries;
    }

    private TickerSummary buildTickerSummary(String ticker) {
        String latestStr = redisTemplate.opsForValue().get(LATEST_KEY_PREFIX + ticker);
        BigDecimal latestPrice = latestStr != null ? new BigDecimal(latestStr) : BigDecimal.ZERO;

        List<String> rawHistory = redisTemplate.opsForList()
                .range(HISTORY_KEY_PREFIX + ticker, 0, WINDOW_SIZE - 1);

        List<BigDecimal> priceHistory = new ArrayList<>();
        if (rawHistory != null) {
            for (String s : rawHistory) {
                priceHistory.add(new BigDecimal(s));
            }
        }

        BigDecimal trendPercent = calculateTrend(priceHistory);

        return new TickerSummary(ticker, latestPrice, priceHistory, trendPercent);
    }

    /**
     * Calculates percentage change from the oldest to the newest value in the window.
     * Returns null if fewer than 2 data points exist.
     */
    static BigDecimal calculateTrend(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return null;
        }
        // newest is at index 0, oldest is at the end
        BigDecimal newest = prices.getFirst();
        BigDecimal oldest = prices.getLast();

        if (oldest.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return newest.subtract(oldest)
                .divide(oldest, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
