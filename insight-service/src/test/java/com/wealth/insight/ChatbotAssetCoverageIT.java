package com.wealth.insight;

import com.wealth.insight.dto.TickerSummary;
import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the {@code chatbot-asset-coverage-fix} spec — Task 7.
 *
 * <p>Validates the full cross-component pipeline using a real Redis container:
 *
 * <ol>
 *   <li><b>Consumer pipeline preservation (req 3.6):</b> {@link InsightEventListener} consuming
 *       a {@link PriceUpdatedEvent} correctly populates Redis with the latest price, a capped
 *       10-item history, and ZSET membership — for both plain and suffixed tickers.</li>
 *   <li><b>Suffix chat resolution (req 2.3–2.6):</b> with Redis seeded for {@code ROSE-USD},
 *       {@code USDCHF=X}, and {@code RELIANCE.NS}, the data layer returns each suffixed symbol
 *       by its exact Redis key ({@code market:latest:{S}}).</li>
 *   <li><b>Plain-symbol chat preservation (req 3.1):</b> plain-symbol data retrieval still
 *       works in the same integration context after the suffix-aware resolver changes.</li>
 *   <li><b>Market summary (req 2.1, 2.2):</b> seeded tickers appear in the market summary
 *       with non-null prices, including suffixed symbols.</li>
 *   <li><b>24-hour stale pruning (req 3.6):</b> the ZSET prunes entries older than 24 hours
 *       on every write, keeping the tracked-tickers set bounded.</li>
 *   <li><b>InsightEventListener → Redis pipeline (req 2.1, 2.2, 3.6):</b> calling
 *       {@link InsightEventListener#onPriceUpdated} directly (simulating Kafka delivery)
 *       populates Redis correctly for both plain and suffixed tickers.</li>
 * </ol>
 *
 * <p>Kafka is not required as a container here: the test drives
 * {@link InsightEventListener#onPriceUpdated} directly to keep the test deterministic and
 * fast without requiring Kafka consumer group coordination. The Kafka → listener wiring is
 * covered by the Spring Kafka integration tests in {@code market-data-service}.
 *
 * <p>Tagged {@code integration} — run via {@code ./gradlew :insight-service:integrationTest}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Disable AI ChatModel auto-configurations — MockAiInsightService handles AI calls.
                "spring.ai.model.chat=none",
                "spring.ai.azure.openai.endpoint=https://placeholder.openai.azure.com/",
                // Disable Kafka listener auto-startup; we drive InsightEventListener directly.
                "spring.kafka.listener.auto-startup=false",
                // Point Kafka bootstrap at an invalid address so KafkaAdmin fails fast
                // instead of hanging for the full connection timeout.
                "spring.kafka.bootstrap-servers=localhost:0"
        }
)
class ChatbotAssetCoverageIT {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @Autowired private MarketDataService marketDataService;
    @Autowired private InsightEventListener insightEventListener;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private AiInsightService aiInsightService;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ── Consumer pipeline preservation — plain symbols (req 3.6) ─────────────────────────

    /**
     * Processing a {@link PriceUpdatedEvent} for a plain symbol populates:
     * <ul>
     *   <li>{@code market:latest:AAPL} with the latest price</li>
     *   <li>{@code market:history:AAPL} with a capped 10-item list (newest at head)</li>
     *   <li>{@code market:tracked-tickers} ZSET with {@code AAPL} as a member</li>
     * </ul>
     */
    @Test
    void consumerPipeline_plainSymbol_populatesLatestHistoryAndZset() {
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("179.00")));

        // Latest price
        String latest = redisTemplate.opsForValue().get(MarketDataService.LATEST_KEY_PREFIX + "AAPL");
        assertThat(latest).isEqualTo("179.00");

        // History (newest at head)
        List<String> history = redisTemplate.opsForList()
                .range(MarketDataService.HISTORY_KEY_PREFIX + "AAPL", 0, -1);
        assertThat(history).containsExactly("179.00", "178.50");

        // ZSET membership
        Double score = redisTemplate.opsForZSet().score(MarketDataService.TRACKED_TICKERS_KEY, "AAPL");
        assertThat(score).isNotNull().isPositive();
    }

    /**
     * History is capped at {@link MarketDataService#WINDOW_SIZE} (10) items.
     * The 11th push trims the oldest entry.
     */
    @Test
    void consumerPipeline_historyCapAt10Items() {
        for (int i = 1; i <= 12; i++) {
            marketDataService.processUpdate(
                    new PriceUpdatedEvent("MSFT", BigDecimal.valueOf(400 + i)));
        }

        List<String> history = redisTemplate.opsForList()
                .range(MarketDataService.HISTORY_KEY_PREFIX + "MSFT", 0, -1);
        assertThat(history)
                .as("history must be capped at %d items", MarketDataService.WINDOW_SIZE)
                .hasSize(MarketDataService.WINDOW_SIZE);
        // Newest (412) at head
        assertThat(history.getFirst()).isEqualTo("412");
    }

    // ── Consumer pipeline preservation — suffixed symbols (req 3.6) ──────────────────────

    /**
     * Consumer pipeline works identically for suffixed symbols (crypto, forex, NSE).
     * Validates req 3.6 for the suffix ticker shapes introduced by Root Cause 2.
     * The exact suffixed key must be used — no stripping or normalization.
     */
    @Test
    void consumerPipeline_suffixedSymbols_populatesExactRedisKeys() {
        marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD",    new BigDecimal("0.0850")));
        marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X",   new BigDecimal("0.9050")));
        marketDataService.processUpdate(new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

        for (String ticker : List.of("ROSE-USD", "USDCHF=X", "RELIANCE.NS")) {
            String latest = redisTemplate.opsForValue()
                    .get(MarketDataService.LATEST_KEY_PREFIX + ticker);
            assertThat(latest)
                    .as("market:latest:%s must be set with the exact suffixed key", ticker)
                    .isNotNull();

            Double score = redisTemplate.opsForZSet()
                    .score(MarketDataService.TRACKED_TICKERS_KEY, ticker);
            assertThat(score)
                    .as("market:tracked-tickers must contain %s with the exact suffixed key", ticker)
                    .isNotNull();
        }
    }

    // ── 24-hour stale pruning (req 3.6) ──────────────────────────────────────────────────

    /**
     * Stale entries (score older than 24 hours) are pruned from the ZSET on every write.
     */
    @Test
    void consumerPipeline_staleTickerPruning_removesOldEntries() {
        // Manually insert a stale entry scored 25 hours ago.
        long staleScore = System.currentTimeMillis() - (25 * 60 * 60 * 1000L);
        redisTemplate.opsForZSet().add(MarketDataService.TRACKED_TICKERS_KEY, "STALE-TICKER", staleScore);

        // A fresh update triggers pruning.
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("180.00")));

        // The stale entry must have been pruned.
        Double staleRemaining = redisTemplate.opsForZSet()
                .score(MarketDataService.TRACKED_TICKERS_KEY, "STALE-TICKER");
        assertThat(staleRemaining)
                .as("STALE-TICKER must be pruned from market:tracked-tickers after 24h TTL")
                .isNull();

        // The fresh entry must still be present.
        Double freshScore = redisTemplate.opsForZSet()
                .score(MarketDataService.TRACKED_TICKERS_KEY, "AAPL");
        assertThat(freshScore).isNotNull();
    }

    // ── Market summary with seeded tickers (req 2.1, 2.2) ────────────────────────────────

    /**
     * After seeding Redis with a set of tickers (simulating what the seed→Kafka→Redis
     * pipeline produces), {@link MarketDataService#getMarketSummary()} returns all seeded
     * tickers with non-null prices — including suffixed symbols.
     */
    @Test
    void marketSummary_returnsAllSeededTickers_withNonNullPrices() {
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL",        new BigDecimal("178.50")));
        marketDataService.processUpdate(new PriceUpdatedEvent("MSFT",        new BigDecimal("420.00")));
        marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD",    new BigDecimal("0.0850")));
        marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X",   new BigDecimal("0.9050")));
        marketDataService.processUpdate(new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

        Map<String, TickerSummary> summary = marketDataService.getMarketSummary();

        assertThat(summary).containsKeys("AAPL", "MSFT", "ROSE-USD", "USDCHF=X", "RELIANCE.NS");
        summary.values().forEach(ts ->
                assertThat(ts.latestPrice())
                        .as("latestPrice for %s must be non-null", ts.ticker())
                        .isNotNull());
    }

    // ── Suffix chat data layer (req 2.3–2.6) ─────────────────────────────────────────────

    /**
     * With Redis seeded for {@code ROSE-USD}, the exact Redis key {@code market:latest:ROSE-USD}
     * holds the correct price. This validates that the data layer supports the suffix resolver
     * fix — the resolver calls {@code getTickerSummary("ROSE-USD")} which reads
     * {@code market:latest:ROSE-USD} verbatim.
     */
    @Test
    void suffixDataLayer_cryptoSymbol_exactKeyLookupReturnsPrice() {
        marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD", new BigDecimal("0.0850")));

        TickerSummary summary = marketDataService.getTickerSummary("ROSE-USD");

        assertThat(summary.latestPrice())
                .as("getTickerSummary(\"ROSE-USD\") must read market:latest:ROSE-USD")
                .isEqualByComparingTo("0.0850");
    }

    /**
     * Same exact-key lookup for forex ({@code USDCHF=X}) and NSE ({@code RELIANCE.NS}).
     */
    @Test
    void suffixDataLayer_forexAndNseSymbols_exactKeyLookupReturnsPrice() {
        marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X",   new BigDecimal("0.9050")));
        marketDataService.processUpdate(new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

        TickerSummary forex = marketDataService.getTickerSummary("USDCHF=X");
        assertThat(forex.latestPrice())
                .as("getTickerSummary(\"USDCHF=X\") must read market:latest:USDCHF=X")
                .isEqualByComparingTo("0.9050");

        TickerSummary nse = marketDataService.getTickerSummary("RELIANCE.NS");
        assertThat(nse.latestPrice())
                .as("getTickerSummary(\"RELIANCE.NS\") must read market:latest:RELIANCE.NS")
                .isEqualByComparingTo("2950.00");
    }

    // ── Plain-symbol data layer preservation (req 3.1) ───────────────────────────────────

    /**
     * Plain-symbol data retrieval still works in the same integration context after the
     * suffix-aware resolver changes. Verifies that the Redis data layer is unchanged for
     * plain symbols.
     */
    @Test
    void plainSymbolDataLayer_stillWorksAfterSuffixChanges() {
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));
        marketDataService.processUpdate(new PriceUpdatedEvent("MSFT", new BigDecimal("420.00")));

        assertThat(marketDataService.getTickerSummary("AAPL").latestPrice())
                .isEqualByComparingTo("178.50");
        assertThat(marketDataService.getTickerSummary("MSFT").latestPrice())
                .isEqualByComparingTo("420.00");

        Map<String, TickerSummary> summary = marketDataService.getMarketSummary();
        assertThat(summary).containsKeys("AAPL", "MSFT");
    }

    // ── InsightEventListener → Redis pipeline (req 2.1, 2.2, 3.6) ───────────────────────

    /**
     * Calling {@link InsightEventListener#onPriceUpdated} directly (simulating Kafka delivery)
     * populates Redis correctly for a plain symbol.
     */
    @Test
    void insightEventListener_plainSymbol_populatesRedis() {
        insightEventListener.onPriceUpdated(new PriceUpdatedEvent("NVDA", new BigDecimal("900.00")));

        String latest = redisTemplate.opsForValue()
                .get(MarketDataService.LATEST_KEY_PREFIX + "NVDA");
        assertThat(latest)
                .as("market:latest:NVDA must be populated after InsightEventListener.onPriceUpdated")
                .isEqualTo("900.00");

        Double score = redisTemplate.opsForZSet()
                .score(MarketDataService.TRACKED_TICKERS_KEY, "NVDA");
        assertThat(score)
                .as("NVDA must appear in market:tracked-tickers after InsightEventListener.onPriceUpdated")
                .isNotNull();
    }

    /**
     * Same pipeline for a suffixed symbol — verifies that {@link InsightEventListener}
     * preserves the exact suffixed key in Redis without stripping or normalizing.
     */
    @Test
    void insightEventListener_suffixedSymbol_preservesExactKeyInRedis() {
        insightEventListener.onPriceUpdated(new PriceUpdatedEvent("BTC-USD", new BigDecimal("64250.00")));

        String latest = redisTemplate.opsForValue()
                .get(MarketDataService.LATEST_KEY_PREFIX + "BTC-USD");
        assertThat(latest)
                .as("market:latest:BTC-USD must use the exact suffixed key (no stripping)")
                .isEqualTo("64250.00");

        Double score = redisTemplate.opsForZSet()
                .score(MarketDataService.TRACKED_TICKERS_KEY, "BTC-USD");
        assertThat(score)
                .as("BTC-USD must appear in market:tracked-tickers with the exact suffixed key")
                .isNotNull();
    }
}
