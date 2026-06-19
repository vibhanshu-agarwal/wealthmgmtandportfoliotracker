package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.kafka.SaslPlainKafkaSupport;
import com.wealth.portfolio.trace.KafkaTracePropagationProbe;
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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Task 3.4 — SASL/PLAIN transport round-trip on a full JRE (guards H2/H3 + SASL auth config).
 *
 * <p>Unlike the slim-image smoke test, this runs on the test JVM's full JRE and therefore does
 * not reproduce the missing {@code java.security.sasl} jlink defect — it validates that the
 * production listener stack projects correctly when SASL authentication is required.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(KafkaTracePropagationProbe.class)
@TestPropertySource(
        properties = {
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.tracing.sampling.probability=1.0",
            "management.tracing.propagation.type=w3c",
            "spring.kafka.template.observation-enabled=true",
            "spring.kafka.listener.observation-enabled=true"
        })
class PriceUpdatedEventSaslTransportIT {

    private static final String TOPIC = "market-prices";
    private static final String DLT_TOPIC = "market-prices.DLT";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(45);
    private static final Instant OBSERVED_AT = Instant.parse("2026-06-18T09:00:00Z");

    @Container
    static final ConfluentKafkaContainer kafka = SaslPlainKafkaSupport.createStandalone();

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.properties.security.protocol", () -> "SASL_PLAINTEXT");
        registry.add("spring.kafka.properties.sasl.mechanism", () -> "PLAIN");
        registry.add("spring.kafka.properties.sasl.jaas.config", SaslPlainKafkaSupport::clientJaasConfig);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired KafkaProperties kafkaProperties;

    private KafkaTemplate<String, PriceUpdatedEvent> saslProducer;
    private KafkaConsumer<String, byte[]> dltConsumer;

    @BeforeEach
    void setUp() {
        KafkaTracePropagationProbe.reset();

        Map<String, Object> producerProps = new HashMap<>(kafkaProperties.buildProducerProperties());
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);

        ProducerFactory<String, PriceUpdatedEvent> producerFactory =
                new DefaultKafkaProducerFactory<>(
                        producerProps, new StringSerializer(), new JacksonJsonSerializer<>());
        saslProducer = new KafkaTemplate<>(producerFactory);

        dltConsumer =
                new KafkaConsumer<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                kafka.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG,
                                "sasl-dlt-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest",
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                ByteArrayDeserializer.class,
                                "security.protocol",
                                "SASL_PLAINTEXT",
                                "sasl.mechanism",
                                "PLAIN",
                                "sasl.jaas.config",
                                SaslPlainKafkaSupport.clientJaasConfig()));
        dltConsumer.subscribe(List.of(DLT_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (saslProducer != null) {
            saslProducer.destroy();
        }
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    void wellFormedEvent_overSaslPlain_projectsToReadModel() throws Exception {
        String ticker = "SASL_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        PriceUpdatedEvent event =
                new PriceUpdatedEvent(
                        ticker,
                        new BigDecimal("231.40"),
                        "USD",
                        OBSERVED_AT,
                        new BigDecimal("229.10"),
                        Instant.parse("2026-06-17T09:00:00Z"));

        saslProducer.send(TOPIC, ticker, event).get();

        Instant observedAtMs = OBSERVED_AT.truncatedTo(ChronoUnit.MILLIS);

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> {
                            Integer priceCount =
                                    jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM market_prices WHERE ticker = ?",
                                            Integer.class,
                                            ticker);
                            assertThat(priceCount).isEqualTo(1);

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
                        });
    }

    @Test
    void malformedEvent_overSaslPlain_routesToDlt_withoutProjection() throws Exception {
        String ticker = "SASL_BAD_" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, BigDecimal.ZERO);

        saslProducer.send(TOPIC, ticker, event).get();

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .until(() -> pollDlt().stream().anyMatch(r -> ticker.equals(r.key())));

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_prices WHERE ticker = ?", Integer.class, ticker);
        assertThat(count).isZero();
    }

    @Test
    void recovery_afterSaslProjection_accumulatesInWindowHistory() throws Exception {
        String ticker = "SASL_REC_" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        Instant referenceAt = Instant.now().minus(25, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        saslProducer
                .send(
                        TOPIC,
                        ticker,
                        new PriceUpdatedEvent(
                                ticker, new BigDecimal("100.00"), "USD", referenceAt, null, null))
                .get();
        saslProducer
                .send(
                        TOPIC,
                        ticker,
                        new PriceUpdatedEvent(
                                ticker, new BigDecimal("110.00"), "USD", observedAt, null, null))
                .get();

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(
                        () -> {
                            Integer historyCount =
                                    jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?",
                                            Integer.class,
                                            ticker);
                            assertThat(historyCount).isEqualTo(2);

                            Timestamp storedRefTs =
                                    jdbcTemplate.queryForObject(
                                            """
                                            SELECT observed_at
                                            FROM market_price_history
                                            WHERE ticker = ?
                                            ORDER BY observed_at ASC
                                            LIMIT 1
                                            """,
                                            Timestamp.class,
                                            ticker);
                            Instant storedRef = storedRefTs.toInstant();
                            long hoursBetween =
                                    ChronoUnit.HOURS.between(storedRef, observedAt);
                            assertThat(hoursBetween).isBetween(18L, 36L);
                        });
    }

    private List<ConsumerRecord<String, byte[]>> pollDlt() {
        var records = dltConsumer.poll(Duration.ofMillis(500));
        List<ConsumerRecord<String, byte[]>> result = new ArrayList<>();
        records.records(DLT_TOPIC).forEach(result::add);
        return result;
    }
}
