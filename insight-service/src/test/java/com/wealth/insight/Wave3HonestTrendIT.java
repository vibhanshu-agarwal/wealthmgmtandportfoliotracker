package com.wealth.insight;

import com.wealth.insight.dto.TickerSummary;
import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Wave 3 Task 8 — {@link MarketDataService} honest trend.
 *
 * <p>Uses a real Redis container (Testcontainers). Kafka is excluded; InsightService is mocked.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Task 8.1 — Replay of the same {@code (ticker, observedAt)} is ignored (dedup by identity).</li>
 *   <li>Task 8.1 — A new {@code observedAt} with an unchanged price is a valid distinct observation.</li>
 *   <li>Task 8.2 — {@code trendPercent} is null when fewer than 2 distinct observations exist.</li>
 *   <li>Task 8.2 — {@code trendPercent} is non-null and correct for ≥2 distinct observations.</li>
 *   <li>Task 8.2 — Unchanged-price new snapshot can yield {@code 0.00%} trend (honest zero).</li>
 *   <li>Task 8.3 — Stale tickers are excluded from {@link MarketDataService#getMarketSummary()} by
 *       score on read, not only pruned on write.</li>
 *   <li>Task 8.1 — Old-shape event (no {@code observedAt}) updates latest price but does not add
 *       an observation to the trend window.</li>
 * </ul>
 *
 * <p>Run via: {@code ./gradlew :insight-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=localhost:0",
                "spring.kafka.listener.auto-startup=false",
                "spring.ai.model.chat=none",
                "spring.ai.azure.openai.endpoint=https://placeholder.openai.azure.com/"
        }
)
@ActiveProfiles("default")
class Wave3HonestTrendIT {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Mock InsightService since it calls portfolio-service via REST
    @MockitoBean
    private InsightService insightService;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ── Task 8.1: replay of same (ticker, observedAt) is a no-op ─────────────

    @Test
    void replayOfSameObservation_doesNotAddDuplicateTrendPoint() {
        Instant obs = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PriceUpdatedEvent event = new PriceUpdatedEvent("REPLAY_TEST", new BigDecimal("100.00"),
                "USD", obs, null, null);

        marketDataService.processUpdate(event);
        marketDataService.processUpdate(event); // replay same observation

        // Observation ZSET should have exactly 1 member
        Long zsetSize = redisTemplate.opsForZSet().zCard(MarketDataService.OBS_KEY_PREFIX + "REPLAY_TEST");
        assertThat(zsetSize)
                .as("Replay of same (ticker, observedAt) must not add a duplicate observation")
                .isEqualTo(1L);

        // Trend requires ≥2 distinct observations → null
        TickerSummary summary = marketDataService.getTickerSummary("REPLAY_TEST");
        assertThat(summary.trendPercent())
                .as("With only 1 distinct observation, trend must be null (not 0.00%)")
                .isNull();
    }

    // ── Task 8.1: new observedAt with unchanged price is a valid distinct observation ──

    @Test
    void newTimestampUnchangedPrice_isValidDistinctObservation() {
        Instant obs1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant obs2 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        PriceUpdatedEvent event1 = new PriceUpdatedEvent("UNCHANGED_PRICE", new BigDecimal("200.00"),
                "USD", obs1, null, null);
        PriceUpdatedEvent event2 = new PriceUpdatedEvent("UNCHANGED_PRICE", new BigDecimal("200.00"),
                "USD", obs2, null, null); // same price, new timestamp

        marketDataService.processUpdate(event1);
        marketDataService.processUpdate(event2);

        Long zsetSize = redisTemplate.opsForZSet().zCard(MarketDataService.OBS_KEY_PREFIX + "UNCHANGED_PRICE");
        assertThat(zsetSize)
                .as("New observedAt with same price must produce a 2nd distinct observation")
                .isEqualTo(2L);

        TickerSummary summary = marketDataService.getTickerSummary("UNCHANGED_PRICE");
        // Trend should be 0.00% (honest zero for unchanged price with 2 distinct observations)
        assertThat(summary.trendPercent())
                .as("Unchanged price with 2 distinct observations should yield 0.00% trend")
                .isNotNull()
                .isEqualByComparingTo("0.00");
    }

    // ── Task 8.2: fewer than 2 distinct observations → trend is null ─────────

    @Test
    void singleObservation_trendIsNull() {
        Instant obs = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        marketDataService.processUpdate(new PriceUpdatedEvent("SINGLE_OBS", new BigDecimal("150.00"),
                "USD", obs, null, null));

        TickerSummary summary = marketDataService.getTickerSummary("SINGLE_OBS");
        assertThat(summary.trendPercent())
                .as("Single observation must yield null trend, not 0.00%")
                .isNull();
    }

    // ── Task 8.2: ≥2 distinct observations → correct trend computed ──────────

    @Test
    void twoDistinctObservations_trendComputedCorrectly() {
        Instant obs1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant obs2 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        marketDataService.processUpdate(new PriceUpdatedEvent("TREND_CALC", new BigDecimal("100.00"),
                "USD", obs1, null, null));
        marketDataService.processUpdate(new PriceUpdatedEvent("TREND_CALC", new BigDecimal("110.00"),
                "USD", obs2, null, null));

        TickerSummary summary = marketDataService.getTickerSummary("TREND_CALC");
        // trend = (110 - 100) / 100 * 100 = 10.00%
        assertThat(summary.trendPercent())
                .as("Trend from 100 → 110 should be +10.00%")
                .isNotNull()
                .isEqualByComparingTo("10.00");
    }

    // ── Task 8.2: old-shape event updates price but adds no observation ───────

    @Test
    void oldShapeEvent_noObservedAt_doesNotAddObservationToTrendWindow() {
        // Old-shape event: 2-arg constructor, no observedAt
        marketDataService.processUpdate(new PriceUpdatedEvent("OLD_SHAPE_TREND", new BigDecimal("300.00")));

        // Latest price should be updated
        TickerSummary summary = marketDataService.getTickerSummary("OLD_SHAPE_TREND");
        assertThat(summary.latestPrice()).isEqualByComparingTo("300.00");

        // But the observation ZSET should be empty (no observedAt → no trend observation)
        Long zsetSize = redisTemplate.opsForZSet().zCard(MarketDataService.OBS_KEY_PREFIX + "OLD_SHAPE_TREND");
        assertThat(zsetSize == null || zsetSize == 0)
                .as("Old-shape event must not add an observation to the trend ZSET")
                .isTrue();
        // Trend should be null (legacy history list also needs ≥2 for trend, but we use null for legacy)
        assertThat(summary.trendPercent())
                .as("Old-shape event must yield null trend")
                .isNull();
    }

    // ── Task 8.3: stale tickers excluded on read from getMarketSummary ────────

    @Test
    void staleTickerExcludedFromMarketSummary_onRead() {
        Instant obs = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Active ticker (updated now)
        marketDataService.processUpdate(new PriceUpdatedEvent("ACTIVE_TICKER", new BigDecimal("500.00"),
                "USD", obs, null, null));

        // Stale ticker: manually add to tracked-tickers with an old score (>24h ago)
        long staleScoreMs = Instant.now().minus(25, ChronoUnit.HOURS).toEpochMilli();
        redisTemplate.opsForZSet().add(MarketDataService.TRACKED_TICKERS_KEY, "STALE_TICKER", staleScoreMs);
        // Also seed a latest price for it so we can verify it's excluded
        redisTemplate.opsForValue().set(MarketDataService.LATEST_KEY_PREFIX + "STALE_TICKER", "999.00");

        Map<String, TickerSummary> summary = marketDataService.getMarketSummary();

        assertThat(summary).containsKey("ACTIVE_TICKER");
        assertThat(summary).doesNotContainKey("STALE_TICKER");
    }

    // ── Sub-millisecond dedup: two instants in the same millisecond → 1 observation ──

    @Test
    void subMillisecondInstants_treatedAsIdenticalObservation() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant withNanos = base.plusNanos(500_000); // same millisecond

        marketDataService.processUpdate(new PriceUpdatedEvent("SUBMS_DEDUP", new BigDecimal("400.00"),
                "USD", base, null, null));
        marketDataService.processUpdate(new PriceUpdatedEvent("SUBMS_DEDUP", new BigDecimal("400.00"),
                "USD", withNanos, null, null));

        Long zsetSize = redisTemplate.opsForZSet().zCard(MarketDataService.OBS_KEY_PREFIX + "SUBMS_DEDUP");
        assertThat(zsetSize)
                .as("Two instants in the same millisecond must produce exactly 1 observation")
                .isEqualTo(1L);

        // Trend must be null (only 1 distinct observation)
        TickerSummary summary = marketDataService.getTickerSummary("SUBMS_DEDUP");
        assertThat(summary.trendPercent()).isNull();
    }
}
