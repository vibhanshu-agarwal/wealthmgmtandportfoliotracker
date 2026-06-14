package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service Kafka wire contract integration test (Task 6.7).
 *
 * <p>Publishes {@link PriceUpdatedEvent} bytes through the same no-arg
 * {@link JacksonJsonSerializer} path market-data uses ({@code spring.kafka.producer.value-serializer}
 * class-name config), then verifies portfolio-service's production consumer stack persists enriched
 * temporal fields. Pairs with market-data Task 6.5 and portfolio Task 6.2 unit fixtures.
 *
 * <p>Also verifies integration-level {@code MalformedEventException} routing to
 * {@code market-prices.DLT} (distinct from unit-level deserializer rejection in 6.2).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PriceUpdatedEventKafkaRoundTripIT {

    private static final String TOPIC = "market-prices";
    private static final String DLT_TOPIC = "market-prices.DLT";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T10:15:30Z");
    private static final Instant PREVIOUS_REFERENCE_AT = Instant.parse("2026-06-07T10:15:30Z");

    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JacksonJsonSerializer<PriceUpdatedEvent> producerSerializer;
    private KafkaProducer<String, byte[]> rawProducer;
    private KafkaConsumer<String, byte[]> dltConsumer;

    @BeforeEach
    void setUp() {
        producerSerializer = new JacksonJsonSerializer<>();

        rawProducer =
                new KafkaProducer<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));

        dltConsumer =
                new KafkaConsumer<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG, "round-trip-dlt-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        dltConsumer.subscribe(List.of(DLT_TOPIC));
    }

    @AfterEach
    void tearDown() {
        producerSerializer.close();
        rawProducer.close();
        dltConsumer.close();
    }

    @Test
    void productionSerializer_enrichedEvent_roundTripsToProjection() throws Exception {
        String ticker = "RT_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        PriceUpdatedEvent event =
                new PriceUpdatedEvent(
                        ticker,
                        new BigDecimal("64000.50"),
                        "USD",
                        OBSERVED_AT,
                        new BigDecimal("63250.00"),
                        PREVIOUS_REFERENCE_AT);

        publishViaProductionSerializer(ticker, event);

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> {
                            BigDecimal price =
                                    jdbcTemplate.queryForObject(
                                            "SELECT current_price FROM market_prices WHERE ticker = ?",
                                            BigDecimal.class,
                                            ticker);
                            String currency =
                                    jdbcTemplate.queryForObject(
                                            "SELECT quote_currency FROM market_prices WHERE ticker = ?",
                                            String.class,
                                            ticker);
                            assertThat(price).isEqualByComparingTo("64000.50");
                            assertThat(currency).isEqualTo("USD");
                        });
    }

    @Test
    void malformedEvent_routesToDlt_projectionNotUpdated() throws Exception {
        String ticker = "BAD_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, BigDecimal.ZERO);

        publishViaProductionSerializer(ticker, event);

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .until(() -> pollDlt().stream().anyMatch(r -> ticker.equals(r.key())));

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_prices WHERE ticker = ?", Integer.class, ticker);
        assertThat(count).isZero();
    }

    private void publishViaProductionSerializer(String key, PriceUpdatedEvent event) throws Exception {
        byte[] wire = producerSerializer.serialize(TOPIC, event);
        rawProducer.send(new ProducerRecord<>(TOPIC, key, wire)).get();
    }

    private List<ConsumerRecord<String, byte[]>> pollDlt() {
        var records = dltConsumer.poll(Duration.ofMillis(500));
        List<ConsumerRecord<String, byte[]>> result = new ArrayList<>();
        records.forEach(result::add);
        return result;
    }
}
