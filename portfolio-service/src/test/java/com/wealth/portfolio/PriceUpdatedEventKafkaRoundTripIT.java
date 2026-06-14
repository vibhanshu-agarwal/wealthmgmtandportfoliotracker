package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
 * <p>Publishes via a {@link KafkaTemplate} backed by a {@link DefaultKafkaProducerFactory} configured
 * like market-data's {@code application.yml} producer ({@code JacksonJsonSerializer} +
 * {@code spring.json.add.type.headers=true}). That reproduces the on-wire JSON body (Task 6.5) and
 * the {@code __TypeId__} header market-data emits, without a cross-module test dependency.
 *
 * <p>Consumes through portfolio-service's production listener/deserializer stack
 * ({@link PortfolioKafkaConfig} + {@link PriceUpdatedEventListener}). Asserts:
 * <ul>
 *   <li>{@code market_prices} receives {@code current_price} and {@code quote_currency}</li>
 *   <li>{@code market_price_history} receives {@code observed_at} (millisecond-truncated) and
 *       {@code price} — the enriched temporal field portfolio actually persists</li>
 *   <li>{@code previousReferencePrice}/{@code previousReferenceAt} are intentionally not asserted;
 *       portfolio does not persist them</li>
 *   <li>Listener-level {@link com.wealth.portfolio.kafka.MalformedEventException} routes to
 *       {@code market-prices.DLT} (distinct from unit-level deserializer rejection in 6.2)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PriceUpdatedEventKafkaRoundTripIT {

    private static final String TOPIC = "market-prices";
    private static final String DLT_TOPIC = "market-prices.DLT";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String TYPE_ID_HEADER = "__TypeId__";

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

    private KafkaTemplate<String, PriceUpdatedEvent> marketDataLikeProducer;
    private KafkaConsumer<String, byte[]> wireSniffer;
    private KafkaConsumer<String, byte[]> dltConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        producerProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);

        marketDataLikeProducer =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        wireSniffer =
                new KafkaConsumer<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG, "wire-sniffer-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        wireSniffer.subscribe(List.of(TOPIC));

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
        if (marketDataLikeProducer != null) {
            marketDataLikeProducer.destroy();
        }
        wireSniffer.close();
        dltConsumer.close();
    }

    @Test
    void marketDataLikeProducer_enrichedEvent_roundTripsToProjection() throws Exception {
        String ticker = "RT_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        PriceUpdatedEvent event =
                new PriceUpdatedEvent(
                        ticker,
                        new BigDecimal("64000.50"),
                        "USD",
                        OBSERVED_AT,
                        new BigDecimal("63250.00"),
                        PREVIOUS_REFERENCE_AT);

        publishViaMarketDataLikeProducer(ticker, event);

        Instant observedAtMs = OBSERVED_AT.truncatedTo(ChronoUnit.MILLIS);

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

                            Integer historyCount =
                                    jdbcTemplate.queryForObject(
                                            """
                                            SELECT COUNT(*)
                                            FROM market_price_history
                                            WHERE ticker = ?
                                              AND observed_at = ?
                                            """,
                                            Integer.class,
                                            ticker,
                                            Timestamp.from(observedAtMs));
                            assertThat(historyCount).isEqualTo(1);

                            BigDecimal historyPrice =
                                    jdbcTemplate.queryForObject(
                                            """
                                            SELECT price
                                            FROM market_price_history
                                            WHERE ticker = ?
                                              AND observed_at = ?
                                            """,
                                            BigDecimal.class,
                                            ticker,
                                            Timestamp.from(observedAtMs));
                            assertThat(historyPrice).isEqualByComparingTo("64000.50");
                        });
    }

    @Test
    void malformedEvent_routesToDlt_projectionNotUpdated() throws Exception {
        String ticker = "BAD_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, BigDecimal.ZERO);

        publishViaMarketDataLikeProducer(ticker, event);

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .until(() -> pollDlt().stream().anyMatch(r -> ticker.equals(r.key())));

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_prices WHERE ticker = ?", Integer.class, ticker);
        assertThat(count).isZero();
    }

    private void publishViaMarketDataLikeProducer(String key, PriceUpdatedEvent event) throws Exception {
        marketDataLikeProducer.send(TOPIC, key, event).get();

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> {
                            ConsumerRecord<String, byte[]> record =
                                    pollWire(TOPIC).stream()
                                            .filter(r -> key.equals(r.key()))
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new AssertionError(
                                                                    "No Kafka record found for key " + key));
                            Header typeHeader = record.headers().lastHeader(TYPE_ID_HEADER);
                            assertThat(typeHeader).isNotNull();
                            assertThat(new String(typeHeader.value())).contains("PriceUpdatedEvent");
                        });
    }

    private List<ConsumerRecord<String, byte[]>> pollWire(String topic) {
        var records = wireSniffer.poll(Duration.ofMillis(500));
        List<ConsumerRecord<String, byte[]>> result = new ArrayList<>();
        records.records(topic).forEach(result::add);
        return result;
    }

    private List<ConsumerRecord<String, byte[]>> pollDlt() {
        var records = dltConsumer.poll(Duration.ofMillis(500));
        List<ConsumerRecord<String, byte[]>> result = new ArrayList<>();
        records.forEach(result::add);
        return result;
    }
}
