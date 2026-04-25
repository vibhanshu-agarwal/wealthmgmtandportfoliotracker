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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MarketDataService, InsightController, and ChatController
 * with real Redis via Testcontainers. Kafka is excluded; InsightService is mocked
 * since it depends on portfolio-service REST calls.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=localhost:0",
                // Prevent listener containers from auto-starting — avoids any broker
                // connection attempt while keeping KafkaProperties in the context so
                // InsightKafkaConfig can build its consumer/listener-container beans.
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles("default")
class MarketSummaryIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // Set REDIS_URL in the format expected by spring.data.redis.url
        // (application.yml now uses url instead of host/port)
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @Autowired private MarketDataService marketDataService;
    @Autowired private AiInsightService aiInsightService;
    @Autowired private StringRedisTemplate redisTemplate;

    // Mock InsightService since it calls portfolio-service via REST
    @MockitoBean private InsightService insightService;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // --- Task 13.1: MarketDataService with real Redis ---

    @Test
    void processUpdate_and_getMarketSummary_roundTrip() {
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("178.50")));
        marketDataService.processUpdate(new PriceUpdatedEvent("AAPL", new BigDecimal("179.00")));
        marketDataService.processUpdate(new PriceUpdatedEvent("MSFT", new BigDecimal("420.00")));

        Map<String, TickerSummary> summary = marketDataService.getMarketSummary();

        assertThat(summary).containsKeys("AAPL", "MSFT");
        assertThat(summary.get("AAPL").priceHistory()).hasSize(2);
        assertThat(summary.get("AAPL").latestPrice()).isEqualByComparingTo("179.00");
        assertThat(summary.get("AAPL").trendPercent()).isNotNull();
        assertThat(summary.get("MSFT").priceHistory()).hasSize(1);
        assertThat(summary.get("MSFT").trendPercent()).isNull(); // only 1 data point
    }

    @Test
    void getMarketSummary_emptyRedis_returnsEmptyMap() {
        Map<String, TickerSummary> summary = marketDataService.getMarketSummary();
        assertThat(summary).isEmpty();
    }

    @Test
    void slidingWindow_trims_to_10_entries() {
        for (int i = 0; i < 15; i++) {
            marketDataService.processUpdate(
                    new PriceUpdatedEvent("GOOG", BigDecimal.valueOf(100 + i)));
        }

        TickerSummary summary = marketDataService.getTickerSummary("GOOG");
        assertThat(summary.priceHistory()).hasSize(10);
        // Newest should be at index 0
        assertThat(summary.priceHistory().getFirst()).isEqualByComparingTo("114");
    }

    // --- Task 13.2: per-ticker endpoint with Redis ---

    @Test
    void getTickerSummary_knownTicker_returnsData() {
        marketDataService.processUpdate(new PriceUpdatedEvent("TSLA", new BigDecimal("250.00")));
        marketDataService.processUpdate(new PriceUpdatedEvent("TSLA", new BigDecimal("252.00")));

        TickerSummary summary = marketDataService.getTickerSummary("TSLA");

        assertThat(summary.ticker()).isEqualTo("TSLA");
        assertThat(summary.latestPrice()).isEqualByComparingTo("252.00");
        assertThat(summary.priceHistory()).hasSize(2);
        assertThat(summary.trendPercent()).isNotNull();
    }

    @Test
    void getTickerSummary_unknownTicker_returnsNullLatestPrice() {
        TickerSummary summary = marketDataService.getTickerSummary("ZZZZ");
        assertThat(summary.latestPrice()).isNull();
    }

    // --- Task 13.3: AiInsightService with mock profile ---

    @Test
    void aiInsightService_mockProfile_returnsDeterministicSentiment() {
        // With default profile (no `bedrock`), MockAiInsightService should be active
        String sentiment = aiInsightService.getSentiment("AAPL");

        assertThat(sentiment).contains("AAPL");
        assertThat(sentiment).contains("Neutral");
    }

    // --- Task 13.3: ChatController logic with real Redis ---

    @Test
    void chatFlow_withKnownTicker_producesConversationalResponse() {
        marketDataService.processUpdate(new PriceUpdatedEvent("NVDA", new BigDecimal("900.00")));

        // Simulate what ChatController does
        TickerSummary summary = marketDataService.getTickerSummary("NVDA");
        assertThat(summary.latestPrice()).isEqualByComparingTo("900.00");

        String sentiment = aiInsightService.getSentiment("NVDA");
        assertThat(sentiment).contains("NVDA");
    }
}
