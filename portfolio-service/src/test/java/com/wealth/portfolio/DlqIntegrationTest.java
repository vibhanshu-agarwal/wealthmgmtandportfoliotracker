package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import com.wealth.market.events.PriceUpdatedEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration tests for the Kafka DLQ pipeline.
 *
 * <p>Spins up real Kafka (KRaft) and PostgreSQL containers via Testcontainers. Verifies that:
 *
 * <ul>
 *   <li>Malformed events (blank ticker) are routed to {@code market-prices.DLT}
 *   <li>Valid events update the {@code market_prices} projection table
 *   <li>Deserialization failures (raw non-JSON bytes) are routed to {@code market-prices.DLT}
 * </ul>
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class DlqIntegrationTest {

  private static final String TOPIC = "market-prices";
  private static final String DLT_TOPIC = "market-prices.DLT";
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

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

  @Autowired JdbcTemplate jdbcTemplate;

  private KafkaProducer<String, byte[]> producer;
  private KafkaConsumer<String, byte[]> dltConsumer;
  private JsonMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = JsonMapper.builder().build();

    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));

    // Unique group per test to avoid offset interference between test runs
    dltConsumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-dlt-verifier-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class));
    dltConsumer.subscribe(List.of(DLT_TOPIC));
  }

  @AfterEach
  void tearDown() {
    producer.close();
    dltConsumer.close();
  }

  // -------------------------------------------------------------------------
  // 8.2 — Malformed message (blank ticker) → DLT, projection not updated
  // -------------------------------------------------------------------------

  @Test
  void blankTicker_routesToDlt_andProjectionNotUpdated() throws Exception {
    String blankTickerJson =
        objectMapper.writeValueAsString(new PriceUpdatedEvent("", new BigDecimal("100.00")));

    producer
        .send(
            new ProducerRecord<>(
                TOPIC, "key-blank", blankTickerJson.getBytes(StandardCharsets.UTF_8)))
        .get();

    // Assert DLT receives the record
    Awaitility.await().atMost(AWAIT_TIMEOUT).until(() -> !pollDlt().isEmpty());

    // Assert projection table has no row for blank ticker
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM market_prices WHERE ticker = ''", Integer.class);
    assertThat(count).isZero();
  }

  // -------------------------------------------------------------------------
  // 8.3 — Valid message → projection updated, nothing on DLT
  // -------------------------------------------------------------------------

  @Test
  void validEvent_updatesProjection_andNothingOnDlt() throws Exception {
    String ticker = "INTG_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String validJson =
        objectMapper.writeValueAsString(new PriceUpdatedEvent(ticker, new BigDecimal("250.00")));

    producer
        .send(new ProducerRecord<>(TOPIC, ticker, validJson.getBytes(StandardCharsets.UTF_8)))
        .get();

    // Assert projection table is updated
    Awaitility.await()
        .atMost(AWAIT_TIMEOUT)
        .untilAsserted(
            () -> {
              Integer count =
                  jdbcTemplate.queryForObject(
                      "SELECT COUNT(*) FROM market_prices WHERE ticker = ?", Integer.class, ticker);
              assertThat(count).isEqualTo(1);
            });

    // Assert nothing arrives on DLT for this ticker within a short window
    List<ConsumerRecord<String, byte[]>> dltRecords = pollDlt();
    boolean dltHasTicker =
        dltRecords.stream()
            .anyMatch(r -> new String(r.value(), StandardCharsets.UTF_8).contains(ticker));
    assertThat(dltHasTicker).isFalse();
  }

  // -------------------------------------------------------------------------
  // 8.4 — Deserialization failure (raw non-JSON bytes) → DLT, consumer survives
  // -------------------------------------------------------------------------

  @Test
  void deserializationFailure_routesToDlt_consumerSurvives() throws Exception {
    byte[] garbage = "not-valid-json-bytes".getBytes(StandardCharsets.UTF_8);
    producer.send(new ProducerRecord<>(TOPIC, "key-deser-fail", garbage)).get();

    // Assert DLT receives the record — consumer must not crash
    Awaitility.await().atMost(AWAIT_TIMEOUT).until(() -> !pollDlt().isEmpty());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private List<ConsumerRecord<String, byte[]>> pollDlt() {
    var records = dltConsumer.poll(Duration.ofMillis(500));
    List<ConsumerRecord<String, byte[]>> result = new ArrayList<>();
    records.forEach(result::add);
    return result;
  }
}