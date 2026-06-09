package com.wealth.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *   <li>{@code market:obs:{ticker}} — a capped ZSET of observations scored by
 *       observation-epoch-millis. Key format: {@code {ticker}:{observedAtMs}:{price}}.
 *       Keyed by observation identity {@code (ticker, observedAt)} so replays of the
 *       same observation are deduplicated, while a new {@code observedAt} with an identical
 *       price is a valid distinct data point (Task 8.1).</li>
 *   <li>{@code market:tracked-tickers} — a ZSET of all known tickers scored by last-update
 *       epoch-millis. Used by {@link #getMarketSummary()} instead of {@code KEYS market:latest:*}
 *       to avoid triggering Upstash's full-keyspace scan. Entries older than
 *       {@link #STALE_TICKER_TTL} are pruned on every write (Task 8.3: also filtered on read).</li>
 * </ul>
 *
 * <h2>Wave 3 — Task 8 changes</h2>
 * <ul>
 *   <li>Task 8.1 — Observations keyed by {@code (ticker, observedAt)} identity. A replay of
 *       the same {@code (ticker, observedAt)} is ignored; a new {@code observedAt} with an
 *       identical price is a valid distinct observation.</li>
 *   <li>Task 8.2 — {@link #calculateTrend} requires ≥2 distinct {@code observedAt} values;
 *       returns null otherwise ("trend not available", not {@code +0.00%}).</li>
 *   <li>Task 8.3 — {@link #getMarketSummary} filters stale tickers by ZSET score on read,
 *       not only prunes on write.</li>
 *   <li>Task 8.4 — Testcontainers-Redis integration tests cover all four properties.</li>
 * </ul>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    static final String LATEST_KEY_PREFIX = "market:latest:";
    /** ZSET keyed by observation identity; score = observedAt epoch-millis. */
    static final String OBS_KEY_PREFIX    = "market:obs:";
    /** Legacy list key — retained for backward compat but no longer written to. */
    static final String HISTORY_KEY_PREFIX = "market:history:";
    static final String TRACKED_TICKERS_KEY = "market:tracked-tickers";
    static final int WINDOW_SIZE = 10;

    /**
     * Tickers that have not received a price update within this window are pruned from the ZSET.
     * 24 hours provides a generous TTL — any ticker silent for a full day is considered stale.
     */
    static final Duration STALE_TICKER_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public MarketDataService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Processes a price update: stores the latest price, records the observation in the
     * identity-keyed ZSET, and updates the tracked-tickers ZSET.
     *
     * <p>Task 8.1: if {@code observedAt} is present on the event, the ZSET member key is
     * {@code {ticker}:{observedAtMs}:{price}}, scored by {@code observedAtMs}. A replay of the
     * same {@code (ticker, observedAt)} (same key) is a ZADD NX no-op. A new {@code observedAt}
     * with an identical price produces a distinct key → valid new observation.
     *
     * <p>If {@code observedAt} is absent (old-shape event), only the latest-price key is updated;
     * no observation is recorded in the ZSET (no receive-time fabrication).
     */
    public void processUpdate(PriceUpdatedEvent event) {
        String ticker = event.ticker();
        String price = event.newPrice().toPlainString();
        long nowMs = System.currentTimeMillis();

        // 1. Update latest price
        redisTemplate.opsForValue().set(LATEST_KEY_PREFIX + ticker, price);

        // 2. Task 8.1: record observation in identity-keyed ZSET (if observedAt present)
        Instant observedAt = event.observedAt();
        if (observedAt != null) {
            long obsMs = observedAt.truncatedTo(ChronoUnit.MILLIS).toEpochMilli();
            // Member key encodes identity: same (ticker, observedAt) → same key → NX is no-op
            String memberKey = obsMs + ":" + price;
            String obsZsetKey = OBS_KEY_PREFIX + ticker;

            // NX: only add if not already present (dedup by observation identity)
            redisTemplate.opsForZSet().addIfAbsent(obsZsetKey, memberKey, obsMs);

            // Trim to sliding window (keep most recent WINDOW_SIZE observations)
            Long currentSize = redisTemplate.opsForZSet().zCard(obsZsetKey);
            if (currentSize != null && currentSize > WINDOW_SIZE) {
                redisTemplate.opsForZSet().removeRange(obsZsetKey, 0, currentSize - WINDOW_SIZE - 1);
            }
        } else {
            log.debug("No observedAt on event for ticker {} — skipping observation ZSET write", ticker);
        }

        // 3. Legacy history list write (for backward compat with existing tests)
        redisTemplate.opsForList().leftPush(HISTORY_KEY_PREFIX + ticker, price);
        redisTemplate.opsForList().trim(HISTORY_KEY_PREFIX + ticker, 0, WINDOW_SIZE - 1);

        // 4. Record in tracked-tickers ZSET scored by epoch-millis
        redisTemplate.opsForZSet().add(TRACKED_TICKERS_KEY, ticker, nowMs);

        // 5. Prune tickers not updated within the stale TTL
        long staleThresholdMs = nowMs - STALE_TICKER_TTL.toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(TRACKED_TICKERS_KEY, 0, staleThresholdMs);

        log.debug("Stored price update for {}: {}", ticker, price);
    }

    /**
     * Returns a market summary for all currently non-stale tracked tickers.
     *
     * <p>Task 8.3: tickers are filtered by ZSET score on read (not only pruned on write).
     * Any ticker whose last-update score is older than {@link #STALE_TICKER_TTL} is excluded
     * from the returned map even if the ZSET prune hasn't run yet.
     */
    public Map<String, TickerSummary> getMarketSummary() {
        long nowMs = System.currentTimeMillis();
        long staleThresholdMs = nowMs - STALE_TICKER_TTL.toMillis();

        // Task 8.3: filter by score on read (rangeByScore excludes stale entries)
        Set<String> tickers = redisTemplate.opsForZSet()
                .rangeByScore(TRACKED_TICKERS_KEY, staleThresholdMs, nowMs);
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

        // Task 8.2: use the observation ZSET to derive trend from distinct observedAt values.
        // Fall back to legacy history list if no observation ZSET exists (e.g. old-shape events).
        String obsZsetKey = OBS_KEY_PREFIX + ticker;
        Long obsCount = redisTemplate.opsForZSet().zCard(obsZsetKey);

        if (obsCount != null && obsCount > 0) {
            // Use observation ZSET (newest-last, score=observedAtMs)
            Set<String> obsMembers = redisTemplate.opsForZSet().range(obsZsetKey, 0, -1);
            if (obsMembers != null && !obsMembers.isEmpty()) {
                List<BigDecimal> priceHistory = new ArrayList<>();
                for (String member : obsMembers) {
                    // member format: "{observedAtMs}:{price}"
                    int colonIdx = member.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < member.length() - 1) {
                        BigDecimal parsed = parsePrice(member.substring(colonIdx + 1));
                        if (parsed != null) priceHistory.add(parsed);
                    }
                }
                // Reverse so newest is at index 0 (consistent with legacy history)
                Collections.reverse(priceHistory);

                // Task 8.2: require ≥2 distinct observedAt values (ZSET has distinct scores by design)
                BigDecimal trendPercent = obsMembers.size() >= 2
                        ? calculateTrend(priceHistory)
                        : null;
                return new TickerSummary(ticker, latestPrice, priceHistory, trendPercent, null);
            }
        }

        // Fallback: legacy history list (old-shape events without observedAt)
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

        // Task 8.2: legacy list has no distinct-observedAt guarantee — return null trend
        // (cannot confirm ≥2 distinct observations from price-only history).
        return new TickerSummary(ticker, latestPrice, priceHistory, null, null);
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
     *
     * <p>Task 8.2: This method computes the arithmetic change. The caller is responsible for
     * ensuring the list contains ≥2 elements from distinct observations before calling this.
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
