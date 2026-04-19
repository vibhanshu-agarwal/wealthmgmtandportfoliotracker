package com.wealth.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
 * <p>Maintains three structures per ticker:
 * <ul>
 *   <li>{@code market:latest:{ticker}} — the most recent price (String key-value)</li>
 *   <li>{@code market:history:{ticker}} — a capped list of the last 10 prices (newest at head)</li>
 *   <li>{@code market:tracked-tickers} — a ZSET of all known tickers scored by last-update
 *       epoch-millis. Used by {@link #getMarketSummary()} instead of {@code KEYS market:latest:*}
 *       to avoid triggering Upstash's full-keyspace scan (which is slow and rate-limited on the
 *       free tier). Entries older than {@link #STALE_TICKER_TTL} are pruned on every write.</li>
 * </ul>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    static final String LATEST_KEY_PREFIX = "market:latest:";
    static final String HISTORY_KEY_PREFIX = "market:history:";
    static final String TRACKED_TICKERS_KEY = "market:tracked-tickers";
    static final int WINDOW_SIZE = 10;

    /**
     * Tickers that have not received a price update within this window are pruned from the ZSET.
     * 24 hours provides a generous TTL — any ticker that has been silent for a full day is
     * considered stale and will not appear in market summaries until it receives a new price.
     */
    static final Duration STALE_TICKER_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public MarketDataService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Processes a price update: stores the latest price, appends to the sliding window, and
     * records the ticker in the tracked-tickers ZSET scored by current time.
     *
     * <p>Stale entries (tickers not updated in the last 24 h) are pruned from the ZSET on
     * every write so the set stays bounded even if the baseline ticker list changes over time.
     */
    public void processUpdate(PriceUpdatedEvent event) {
        String ticker = event.ticker();
        String price = event.newPrice().toPlainString();
        long nowMs = System.currentTimeMillis();

        // 1. Update latest price
        redisTemplate.opsForValue().set(LATEST_KEY_PREFIX + ticker, price);

        // 2. Push to history list (newest at head)
        redisTemplate.opsForList().leftPush(HISTORY_KEY_PREFIX + ticker, price);

        // 3. Trim to sliding window size
        redisTemplate.opsForList().trim(HISTORY_KEY_PREFIX + ticker, 0, WINDOW_SIZE - 1);

        // 4. Record in ZSET scored by epoch-millis (replaces KEYS scan in getMarketSummary)
        redisTemplate.opsForZSet().add(TRACKED_TICKERS_KEY, ticker, nowMs);

        // 5. Prune tickers not updated within the stale TTL
        long staleThresholdMs = nowMs - STALE_TICKER_TTL.toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(TRACKED_TICKERS_KEY, 0, staleThresholdMs);

        log.debug("Stored price update for {}: {}", ticker, price);
    }

    /**
     * Returns a market summary for all tracked tickers.
     *
     * <p>Uses the {@code market:tracked-tickers} ZSET instead of {@code KEYS market:latest:*}.
     * {@code KEYS} performs a full keyspace scan on Upstash, which is both slow and subject to
     * Upstash's per-command rate limits. The ZSET approach is O(N) on the number of tracked
     * tickers only, not the total keyspace, and is not rate-limited differently from other reads.
     */
    public Map<String, TickerSummary> getMarketSummary() {
        Set<String> tickers = redisTemplate.opsForZSet().range(TRACKED_TICKERS_KEY, 0, -1);
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, TickerSummary> summaries = new LinkedHashMap<>();
        for (String ticker : tickers) {
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
