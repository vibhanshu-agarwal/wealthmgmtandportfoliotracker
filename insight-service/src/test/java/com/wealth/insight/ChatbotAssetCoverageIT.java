package com.wealth.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers integration test for the {@code chatbot-asset-coverage-fix} spec — Task 7.
 *
 * <p>This test covers three distinct integration layers, each building on the previous:
 *
 * <ol>
 *   <li><b>Redis data layer (req 2.1, 2.2, 3.1, 3.6):</b> {@link MarketDataService} and {@link
 *       InsightEventListener} correctly populate Redis with the latest price, a capped 10-item
 *       history, and ZSET membership — for both plain and suffixed tickers. Includes 24-hour stale
 *       pruning and market summary coverage.
 *   <li><b>Real Kafka → listener → Redis pipeline (req 2.1, 2.2, 3.6):</b> publishing a {@link
 *       PriceUpdatedEvent} to the embedded Kafka {@code market-prices} topic and consuming it via
 *       the live {@link InsightEventListener} container populates Redis correctly. This validates
 *       the actual Kafka consumer wiring inside insight-service, not just direct method calls.
 *   <li><b>Redis-backed chat endpoint (req 2.3–2.6, 3.1):</b> with Redis seeded for {@code
 *       ROSE-USD}, {@code USDCHF=X}, and {@code RELIANCE.NS}, the {@code POST /api/chat} endpoint
 *       resolves each suffixed symbol to its exact tracked form and returns its market summary.
 *       Plain-symbol chat is also verified in the same context.
 * </ol>
 *
 * <p><b>What is intentionally out of scope:</b> cross-service seed → Kafka → Redis propagation
 * (i.e. invoking {@code MarketDataSeedService} in market-data-service and asserting Redis in
 * insight-service) requires both services in the same JVM, which is not the project's integration
 * test model. That path is covered by the combination of {@code
 * MarketDataSeedServicePropagationHardeningTest} (seed publishes correct events) and the Kafka →
 * Redis tests here (insight-service consumes and stores them correctly).
 *
 * <p>Tagged {@code integration} — run via {@code ./gradlew :insight-service:integrationTest}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      // Use MockAiInsightService — no Bedrock/Azure credentials needed.
      "spring.ai.model.chat=none",
      "spring.ai.openai.base-url=https://placeholder.openai.azure.com/",
      "spring.ai.openai.api-key=placeholder-key",
      // Kafka listener auto-startup is enabled, so the embedded broker wires up.
      // The @EmbeddedKafka annotation below provides the broker.
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@EmbeddedKafka(partitions = 1, topics = "market-prices")
class ChatbotAssetCoverageIT {

  private static final int REDIS_PORT = 6379;

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.redis.url",
        () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
  }

  @Autowired private MarketDataService marketDataService;
  @Autowired private InsightEventListener insightEventListener;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private AiInsightService aiInsightService;
  @Autowired private ChatController chatController;
  @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

  /**
   * A JSON-serializing KafkaTemplate built from the embedded broker's producer config.
   * insight-service is a consumer-only service, so its autoconfigured KafkaTemplate uses
   * StringSerializer. We build a separate producer here with JsonSerializer so we can publish
   * PriceUpdatedEvent records in the Kafka → listener → Redis tests.
   */
  private KafkaTemplate<String, PriceUpdatedEvent> jsonKafkaTemplate;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(chatController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    // Build a JSON-serializing producer against the embedded broker.
    // insight-service has no producer auto-config (consumer-only), so we create one here.
    Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
    var producerFactory =
        new DefaultKafkaProducerFactory<String, PriceUpdatedEvent>(
            producerProps,
            new org.apache.kafka.common.serialization.StringSerializer(),
            new JacksonJsonSerializer<>());
    jsonKafkaTemplate = new KafkaTemplate<>(producerFactory);

    var keys = redisTemplate.keys("market:*");
    if (keys != null && !keys.isEmpty()) {
      redisTemplate.delete(keys);
    }
  }

  // ── Layer 1: Redis data layer — plain symbols (req 3.6) ──────────────────────────────

  /**
   * Processing a {@link PriceUpdatedEvent} for a plain symbol populates:
   *
   * <ul>
   *   <li>{@code market:latest:AAPL} with the latest price
   *   <li>{@code market:history:AAPL} with a capped 10-item list (newest at head)
   *   <li>{@code market:tracked-tickers} ZSET with {@code AAPL} as a member
   * </ul>
   */
  @Test
  void consumerPipeline_plainSymbol_populatesLatestHistoryAndZset() {
    marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));
    marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("179.00")));

    assertThat(redisTemplate.opsForValue().get(MarketDataService.LATEST_KEY_PREFIX + "AAPL"))
        .isEqualTo("179.00");

    List<String> history =
        redisTemplate.opsForList().range(MarketDataService.HISTORY_KEY_PREFIX + "AAPL", 0, -1);
    assertThat(history).containsExactly("179.00", "178.50");

    assertThat(redisTemplate.opsForZSet().score(MarketDataService.TRACKED_TICKERS_KEY, "AAPL"))
        .isNotNull()
        .isPositive();
  }

  /** History is capped at {@link MarketDataService#WINDOW_SIZE} (10) items. */
  @Test
  void consumerPipeline_historyCapAt10Items() {
    for (int i = 1; i <= 12; i++) {
      marketDataService.processUpdate(new PriceUpdatedEvent("MSFT", BigDecimal.valueOf(400 + i)));
    }

    List<String> history =
        redisTemplate.opsForList().range(MarketDataService.HISTORY_KEY_PREFIX + "MSFT", 0, -1);
    assertThat(history)
        .as("history must be capped at %d items", MarketDataService.WINDOW_SIZE)
        .hasSize(MarketDataService.WINDOW_SIZE);
    assertThat(history.getFirst()).isEqualTo("412");
  }

  // ── Layer 1: Redis data layer — suffixed symbols (req 3.6) ───────────────────────────

  /**
   * Consumer pipeline works identically for suffixed symbols. The exact suffixed key must be used —
   * no stripping or normalization.
   */
  @Test
  void consumerPipeline_suffixedSymbols_populatesExactRedisKeys() {
    marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD", new BigDecimal("0.0850")));
    marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X", new BigDecimal("0.9050")));
    marketDataService.processUpdate(
        new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

    for (String ticker : List.of("ROSE-USD", "USDCHF=X", "RELIANCE.NS")) {
      assertThat(redisTemplate.opsForValue().get(MarketDataService.LATEST_KEY_PREFIX + ticker))
          .as("market:latest:%s must be set with the exact suffixed key", ticker)
          .isNotNull();
      assertThat(redisTemplate.opsForZSet().score(MarketDataService.TRACKED_TICKERS_KEY, ticker))
          .as("market:tracked-tickers must contain %s", ticker)
          .isNotNull();
    }
  }

  // ── Layer 1: 24-hour stale pruning (req 3.6) ─────────────────────────────────────────

  @Test
  void consumerPipeline_staleTickerPruning_removesOldEntries() {
    long staleScore = System.currentTimeMillis() - (25 * 60 * 60 * 1000L);
    redisTemplate
        .opsForZSet()
        .add(MarketDataService.TRACKED_TICKERS_KEY, "STALE-TICKER", staleScore);

    marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("180.00")));

    assertThat(
            redisTemplate.opsForZSet().score(MarketDataService.TRACKED_TICKERS_KEY, "STALE-TICKER"))
        .as("STALE-TICKER must be pruned after 24h TTL")
        .isNull();
    assertThat(redisTemplate.opsForZSet().score(MarketDataService.TRACKED_TICKERS_KEY, "AAPL"))
        .isNotNull();
  }

  // ── Layer 1: Market summary (req 2.1, 2.2) ───────────────────────────────────────────

  @Test
  void marketSummary_returnsAllSeededTickers_withNonNullPrices() {
    marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));
    marketDataService.processUpdate(new PriceUpdatedEvent("MSFT", new BigDecimal("420.00")));
    marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD", new BigDecimal("0.0850")));
    marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X", new BigDecimal("0.9050")));
    marketDataService.processUpdate(
        new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

    Map<String, TickerSummary> summary = marketDataService.getMarketSummary();

    assertThat(summary).containsKeys("AAPL", "MSFT", "ROSE-USD", "USDCHF=X", "RELIANCE.NS");
    summary
        .values()
        .forEach(
            ts ->
                assertThat(ts.latestPrice())
                    .as("latestPrice for %s must be non-null", ts.ticker())
                    .isNotNull());
  }

  // ── Layer 2: Real Kafka → InsightEventListener → Redis (req 2.1, 2.2, 3.6) ──────────

  /**
   * Publishing a {@link PriceUpdatedEvent} to the embedded Kafka {@code market-prices} topic and
   * consuming it via the live {@link InsightEventListener} populates Redis correctly for a plain
   * symbol.
   *
   * <p>This validates the actual Kafka consumer wiring inside insight-service — not just direct
   * method calls. Awaitility polls until Redis is populated or the timeout expires.
   */
  @Test
  void kafkaToRedis_plainSymbol_listenerConsumesAndPopulatesRedis() throws Exception {
    jsonKafkaTemplate.send(
        "market-prices", "NVDA", new PriceUpdatedEvent("NVDA", new BigDecimal("900.00")));

    // Both assertions are inside untilAsserted so Awaitility retries until the listener
    // thread has completed ALL writes in processUpdate() — not just the first one.
    // Without this, the OS can context-switch the listener thread away after writing
    // market:latest but before the ZSET add, causing the post-await ZSET check to race.
    await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              String latest =
                  redisTemplate.opsForValue().get(MarketDataService.LATEST_KEY_PREFIX + "NVDA");
              assertThat(latest)
                  .as("market:latest:NVDA must be populated after Kafka consumption")
                  .isEqualTo("900.00");

              assertThat(
                      redisTemplate
                          .opsForZSet()
                          .score(MarketDataService.TRACKED_TICKERS_KEY, "NVDA"))
                  .as("NVDA must appear in market:tracked-tickers after Kafka consumption")
                  .isNotNull();
            });
  }

  /**
   * Same Kafka → listener → Redis pipeline for a suffixed symbol. Validates that the listener
   * preserves the exact suffixed key in Redis without stripping or normalizing.
   */
  @Test
  void kafkaToRedis_suffixedSymbol_listenerPreservesExactKey() throws Exception {
    jsonKafkaTemplate.send(
        "market-prices", "BTC-USD", new PriceUpdatedEvent("BTC-USD", new BigDecimal("64250.00")));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              String latest =
                  redisTemplate.opsForValue().get(MarketDataService.LATEST_KEY_PREFIX + "BTC-USD");
              assertThat(latest)
                  .as("market:latest:BTC-USD must use the exact suffixed key")
                  .isEqualTo("64250.00");
            });
  }

  // ── Layer 3: Redis-backed chat endpoint — suffixed symbols (req 2.3–2.6) ─────────────

  /**
   * With Redis seeded for {@code ROSE-USD}, {@code POST /api/chat} resolves the suffixed symbol to
   * its exact tracked form and returns its market summary.
   *
   * <p>This is the end-to-end path: HTTP request → suffix-aware resolver → Redis-backed {@link
   * MarketDataService#getTickerSummary} → chat response.
   */
  @Test
  void chatEndpoint_cryptoSuffix_resolvesAndReturnsSummary() throws Exception {
    marketDataService.processUpdate(new PriceUpdatedEvent("ROSE-USD", new BigDecimal("0.0850")));

    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"message": "How is ROSE-USD doing?"}
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response", containsString("ROSE-USD")))
        .andExpect(jsonPath("$.response", containsString("0.0850")));
  }

  /** Same end-to-end chat test for a forex {@code =X} symbol. */
  @Test
  void chatEndpoint_forexSuffix_resolvesAndReturnsSummary() throws Exception {
    marketDataService.processUpdate(new PriceUpdatedEvent("USDCHF=X", new BigDecimal("0.9050")));

    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"message": "How is USDCHF=X doing?"}
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response", containsString("USDCHF=X")))
        .andExpect(jsonPath("$.response", containsString("0.9050")));
  }

  /** Same end-to-end chat test for an NSE {@code .NS} symbol. */
  @Test
  void chatEndpoint_nseSuffix_resolvesAndReturnsSummary() throws Exception {
    marketDataService.processUpdate(
        new PriceUpdatedEvent("RELIANCE.NS", new BigDecimal("2950.00")));

    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"message": "Tell me about RELIANCE.NS"}
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response", containsString("RELIANCE.NS")))
        .andExpect(jsonPath("$.response", containsString("2950")));
  }

  // ── Layer 3: Redis-backed chat endpoint — plain symbol preservation (req 3.1) ─────────

  /**
   * Plain-symbol chat still works in the same integration context after the suffix-aware resolver
   * changes. Verifies the full path: HTTP → resolver → Redis → response.
   */
  @Test
  void chatEndpoint_plainSymbol_stillResolvesAfterSuffixChanges() throws Exception {
    marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));

    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"message": "How is AAPL doing?"}
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response", containsString("AAPL")))
        .andExpect(jsonPath("$.response", containsString("178.5")));
  }

  /**
   * A catalog-known suffixed symbol with no Redis data returns the no-data response.
   *
   * <p>In the new catalog-first system, {@code ROSE-USD} is in the catalog so it resolves via
   * preflight, then {@link ChatResponseBuilder} looks up Redis and finds nothing → "I don't have
   * any live data for ROSE-USD right now." (Outcome.NO_DATA).
   */
  @Test
  void chatEndpoint_untrackedSuffixedSymbol_returnsNoData() throws Exception {
    // ROSE-USD is in the catalog but NOT seeded in Redis for this test.
    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"message": "How is ROSE-USD doing?"}
                                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response", containsString("don't have any live data")))
        .andExpect(jsonPath("$.response", containsString("ROSE-USD")));
  }
}
