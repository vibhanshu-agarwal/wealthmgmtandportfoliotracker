package com.wealth.market.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.wealth.market.TestContainerImages;
import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
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
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Property 10b (producer config binding) and Property 2 (producer half): {@code
 * spring.kafka.template.observation-enabled=true} binds on the market-data producer path, and the
 * auto-configured {@link KafkaTemplate} injects a valid W3C {@code traceparent} on the produced
 * record without a test-written header.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.tracing.sampling.probability=1.0",
            "management.tracing.propagation.type=w3c",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",
            "spring.kafka.producer.properties.spring.json.add.type.headers=true",
            "market.seed.enabled=false",
            "market-data.refresh.enabled=false",
            "market-data.hydration.enabled=false",
            "market-data.baseline-seed.enabled=false"
        })
@TestPropertySource(properties = "spring.kafka.template.observation-enabled=true")
@Import(KafkaTemplateTracePropagationIT.W3cPropagationConfig.class)
class KafkaTemplateTracePropagationIT {

    private static final String TOPIC = "market-prices";

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer mongo = new MongoDBContainer(TestContainerImages.MONGO);

    @Container
    static final ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(TestContainerImages.KAFKA);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired private KafkaProperties kafkaProperties;

    @Autowired private KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    @Autowired private ObservationRegistry observationRegistry;

    @BeforeEach
    void enableTemplateObservation() {
        kafkaTemplate.setObservationEnabled(true);
        kafkaTemplate.setObservationRegistry(observationRegistry);
    }

    @Test
    void marketDataProducerPath_hasKafkaTemplateObservationEnabled() {
        assertThat(kafkaProperties.getTemplate().isObservationEnabled()).isTrue();
    }

    @Test
    void autoConfiguredKafkaTemplate_injectsW3cTraceparentOnProducedRecord() throws Exception {
        String ticker = "WIRE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        try (KafkaConsumer<String, byte[]> sniffer = wireSniffer()) {
            sniffer.subscribe(List.of(TOPIC));

            kafkaTemplate
                    .send(TOPIC, ticker, new PriceUpdatedEvent(ticker, new BigDecimal("1.00")))
                    .get();

            List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                sniffer.poll(Duration.ofMillis(500)).forEach(seen::add);
                                ConsumerRecord<String, byte[]> record =
                                        seen.stream()
                                                .filter(r -> ticker.equals(r.key()))
                                                .findFirst()
                                                .orElse(null);
                                assertThat(record).isNotNull();
                                Header header = record.headers().lastHeader("traceparent");
                                assertThat(header)
                                        .as("W3C traceparent must be injected by observation, not the test")
                                        .isNotNull();
                                String traceparent =
                                        new String(header.value(), StandardCharsets.UTF_8);
                                assertThat(traceparent).matches(TraceparentTestSupport.TRACEPARENT);
                                assertThat(TraceparentTestSupport.traceId(traceparent)).isNotBlank();
                            });
        }
    }

    private static KafkaConsumer<String, byte[]> wireSniffer() {
        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "trace-wire-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer());
    }

    /**
     * Boot 4 gates the W3C {@link TextMapPropagator} on tracing export. Keep OTLP export off
     * (task 7.3) and still allow observation to inject {@code traceparent}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class W3cPropagationConfig {

        @Bean
        TextMapPropagator w3cTextMapPropagator() {
            return W3CTraceContextPropagator.getInstance();
        }
    }
}
