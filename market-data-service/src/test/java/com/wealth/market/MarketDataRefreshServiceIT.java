package com.wealth.market;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wealth.market.events.PriceUpdatedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=true",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false"
})
class MarketDataRefreshServiceIT {

    private static final String TOPIC = "market-prices";

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static WireMockServer wireMockServer;

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("external-market-data.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("market-data.refresh.enabled", () -> false);
        registry.add("market-data.job-runner.enabled", () -> false);
        registry.add("market-data.seed.enabled", () -> false);
        registry.add("market-data.baseline-seed.enabled", () -> false);
        registry.add("market.seed.enabled", () -> false);
        registry.add("market.baseline.tickers[0]", () -> "AAPL");
    }

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        String responseBody = """
            {
              "quoteResponse": {
                "result": [
                  {"symbol": "AAPL", "regularMarketPrice": 150.0}
                ]
              }
            }
            """;

        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Autowired MarketDataRefreshService refreshService;
    @Autowired AssetPriceRepository assetPriceRepository;

    @Test
    void refreshPersistsAssetPriceAndPublishesPriceUpdatedEvent() {
        try (KafkaConsumer<String, PriceUpdatedEvent> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            refreshService.refresh();

            AssetPrice saved = assetPriceRepository.findById("AAPL").orElseThrow();
            assertThat(saved.getCurrentPrice()).isEqualByComparingTo("150.0");

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
                ConsumerRecord<String, PriceUpdatedEvent> record = records.iterator().next();
                assertThat(record.key()).isEqualTo("AAPL");
                assertThat(record.value().ticker()).isEqualTo("AAPL");
                assertThat(record.value().newPrice()).isEqualByComparingTo("150.0");
                assertThat(record.value().observedAt()).isNotNull();
            });
        }
    }

    private static KafkaConsumer<String, PriceUpdatedEvent> kafkaConsumer() {
        var deserializer = new JacksonJsonDeserializer<>(PriceUpdatedEvent.class);
        deserializer.addTrustedPackages("com.wealth.market.events");
        deserializer.setUseTypeMapperForKey(false);

        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "refresh-it-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                deserializer);
    }
}
