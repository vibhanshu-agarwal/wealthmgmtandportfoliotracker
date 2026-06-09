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
 *   <li>{@code market:obs:{ticker}} — a capped ZSET of observations keyed and scored
 *       by {@code observedAt} epoch-millis. The <em>member</em> is {@code String.valueOf(obsMs)}
 *       (the observation timestamp alone), so the identity key is strictly
 *       {@code (ticker, observedAt)}: two events with the same {@code observedAt} but
 *       different prices produce the same member → {@code addIfAbsent} is a no-op.
 *       Prices are stored alongside in {@code market:obs:price:{ticker}} as a Redis Hash
 *       keyed by the same {@code obsMs} string, so the latest price for each observation
 *       can be read without embedding price in the member. (Task 8.1)</li>
 *   <li>{@code market:tracked-tickers} — a ZSET of all known tickers scored by last-update
 *       epoch-millis. Used by {@link #getMarketSummary()} instead of {@code KEYS market:latest:*}
 *       to avoid triggering Upstash's full-keyspace scan. Entries older than
 *       {@link #STALE_TICKER_TTL} are pruned on every write (Task 8.3: also filtered on read).</li>
 *   <li>{@code market:history:{ticker}} — legacy capped list of the last 10 prices (newest at
 *       head). Retained for backward compatibility with existing callers; new code should use
 *       the observation ZSET.</li>
 * </ul>
 *
 * <h2>Wave 3 — Task 8 changes</h2>
 * <ul>
 *   <li>Task 8.1 — Observations keyed by {@code (ticker, observedAt)} identity (member =
 *       {@code obsMs} string). A replay of the same {@code (ticker, observedAt)} is ignored;
 *       a new {@code observedAt} with an identical price is a valid distinct observation.</li>
 *   <li>Task 8.2 — Trend gated on the count of distinct ZSET scores (= distinct
 *       {@code observedAt} values); ≥2 required, otherwise null.</li>
 *   <li>Task 8.3 — {@link #getMarketSummary} filters stale tickers by ZSET score on read,
 *       not only prunes on write.</li>
 *   <li>Task 8.4 — Testcontainers-Redis integration tests cover all four properties.</li>
 * </ul>
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    static final String LATEST_KEY_PREFIX    = "market:latest:";
    /** ZSET of observation timestamps; member = obsMs string; score = obsMs. */
    static final String OBS_KEY_PREFIX       = "market:obs:";
    /** Hash of obsMs → price strings for the observation ZSET. */
    static final String OBS_PRICE_KEY_PREFIX = "market:obs:price:";
    /** Legacy capped list (newest at head). Still written for backward compatibility. */
    static final String HISTORY_KEY_PREFIX   = "market:history:";
    static final String TRACKED_TICKERS_KEY  = "market:tracked-tickers";
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
     * <p>Task 8.1: if {@code observedAt} is present, the ZSET member is {@code String.valueOf(obsMs)}
     * (the ms-truncated timestamp string). Identity is {@code (ticker, observedAt)} — the member
     * contains <em>only the timestamp</em>, never the price. This means:
     * <ul>
     *   <li>A replay of the same {@code (ticker, observedAt)} with any price → {@code addIfAbsent}
     *       is a no-op (correct dedup).</li>
     *   <li>A new {@code observedAt} with an identical price → different member → valid new row.</li>
     * </ul>
     * The price is stored separately in the {@code market:obs:price:{ticker}} Hash under the same
     * {@code obsMs} key so it can be read during trend computation.
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
            // Member is the timestamp string ONLY — price is NOT part of the key.
            // Identity = (ticker, observedAt): same obsMs → same member → addIfAbsent no-op.
            String obsMember = String.valueOf(obsMs);
            String obsZsetKey  = OBS_KEY_PREFIX + ticker;
            String obsPriceKey = OBS_PRICE_KEY_PREFIX + ticker;

            // NX: add only if this observedAt has not been seen before
            Boolean added = redisTemplate.opsForZSet().addIfAbsent(obsZsetKey, obsMember, obsMs);
            if (Boolean.TRUE.equals(added)) {
                // Store the price for this observation (overwrite is fine — same obsMs, latest price)
                redisTemplate.opsForHash().put(obsPriceKey, obsMember, price);
            }

            // Trim to sliding window: keep most recent WINDOW_SIZE observations (highest scores)
            Long currentSize = redisTemplate.opsForZSet().zCard(obsZsetKey);
            if (currentSize != null && currentSize > WINDOW_SIZE) {
                // removeRange(0, excess-1) removes the oldest (lowest-scored) entries
                Set<String> toRemove = redisTemplate.opsForZSet()
                        .range(obsZsetKey, 0, currentSize - WINDOW_SIZE - 1);
                if (toRemove != null && !toRemove.isEmpty()) {
                    redisTemplate.opsForZSet().removeRange(obsZsetKey, 0, currentSize - WINDOW_SIZE - 1);
                    redisTemplate.opsForHash().delete(obsPriceKey, toRemove.toArray());
                }
            }
        } else {
            log.debug("No observedAt on event for ticker {} — skipping observation ZSET write", ticker);
        }

        // 3. Legacy history list write (backward compatibility)
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

        // Task 8.2: use the observation ZSET for trend — each member is a distinct observedAt.
        // Fall back to legacy history list if no observation ZSET exists (old-shape events).
        String obsZsetKey  = OBS_KEY_PREFIX + ticker;
        String obsPriceKey = OBS_PRICE_KEY_PREFIX + ticker;
        Long obsCount = redisTemplate.opsForZSet().zCard(obsZsetKey);

        if (obsCount != null && obsCount > 0) {
            // Members are obsMs strings, ordered oldest→newest by score
            Set<String> obsMembers = redisTemplate.opsForZSet().range(obsZsetKey, 0, -1);
            if (obsMembers != null && !obsMembers.isEmpty()) {
                List<BigDecimal> priceHistory = new ArrayList<>();
                for (String member : obsMembers) {
                    // Prices are stored in the companion hash (not embedded in the member)
                    Object raw = redisTemplate.opsForHash().get(obsPriceKey, member);
                    BigDecimal parsed = parsePrice(raw != null ? raw.toString() : null);
                    if (parsed != null) priceHistory.add(parsed);
                }
                // Reverse so newest is at index 0 (consistent with legacy history ordering)
                Collections.reverse(priceHistory);

                // Task 8.2: distinct observedAt count = ZSET member count (each member is
                // one unique obsMs). Gate on ≥2 DISTINCT members (scores), not member count
                // of a price list that might have fewer entries due to missing hash values.
                long distinctObsCount = obsMembers.size(); // each member = one distinct observedAt
                BigDecimal trendPercent = distinctObsCount >= 2
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

        // Task 8.2: legacy list has no distinct-observedAt guarantee — return null trend.
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
