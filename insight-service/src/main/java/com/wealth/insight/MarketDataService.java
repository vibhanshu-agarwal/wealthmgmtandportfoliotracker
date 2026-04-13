package com.wealth.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.wealth.insight.dto.TickerSummary;
import com.wealth.market.events.PriceUpdatedEvent;

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
            try {
                summaries.put(ticker, buildTickerSummary(ticker));
            } catch (Exception e) {
                log.warn("Failed to build summary for ticker '{}', skipping: {}", ticker, e.getMessage());
            }
        }
        return summaries;
    }

    /**
     * Returns a summary for a single ticker, or a summary with null latestPrice if no data exists.
     */
    public TickerSummary getTickerSummary(String ticker) {
        return buildTickerSummary(ticker);
    }

    private TickerSummary buildTickerSummary(String ticker) {
        BigDecimal latestPrice = parsePrice(
                redisTemplate.opsForValue().get(LATEST_KEY_PREFIX + ticker));

        List<String> rawHistory = redisTemplate.opsForList()
                .range(HISTORY_KEY_PREFIX + ticker, 0, WINDOW_SIZE - 1);

        if (rawHistory == null || rawHistory.isEmpty()) {
            return new TickerSummary(ticker, latestPrice, Collections.emptyList(), null, null);
        }

        List<BigDecimal> priceHistory = new ArrayList<>();
        for (String s : rawHistory) {
            BigDecimal parsed = parsePrice(s);
            if (parsed != null) {
                priceHistory.add(parsed);
            }
        }

        if (priceHistory.isEmpty()) {
            return new TickerSummary(ticker, latestPrice, Collections.emptyList(), null, null);
        }

        BigDecimal trendPercent = calculateTrend(priceHistory);
        return new TickerSummary(ticker, latestPrice, priceHistory, trendPercent, null);
    }

    /**
     * Safely parses a price string from Redis. Returns null for null, blank, or malformed values.
     */
    BigDecimal parsePrice(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("Skipping malformed price value in Redis: '{}'", value);
            return null;
        }
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
