package com.wealth.insight.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.wealth.insight.TestContainerImages;
import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Property 10b (insight consumer): listener observation exposes an active span when a W3C
 * {@code traceparent} control header is present on the record.
 *
 * <p>Property 2 (consumer half): observation-injected {@code traceparent} continues the same
 * trace ID under a distinct consumer span. Does not hand-stamp the header.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.ai.model.chat=none",
            "spring.ai.openai.base-url=https://placeholder.openai.azure.com/",
            "spring.ai.openai.api-key=placeholder-key",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.template.observation-enabled=true",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",
            "spring.kafka.producer.properties.spring.json.add.type.headers=true"
        })
@EmbeddedKafka(partitions = 1, topics = "market-prices")
@Import({
    InsightKafkaTracePropagationProbe.class,
    KafkaTraceContextPropagationIT.W3cPropagationConfig.class
})
@TestPropertySource(
        properties = {
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.tracing.sampling.probability=1.0",
            "management.tracing.propagation.type=w3c",
            "spring.kafka.listener.observation-enabled=true"
        })
class KafkaTraceContextPropagationIT {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private Tracer tracer;

    @Autowired private ObservationRegistry observationRegistry;

    @Autowired private KafkaTemplate<String, PriceUpdatedEvent> observedProducer;

    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    @BeforeEach
    void setUp() {
        InsightKafkaTracePropagationProbe.reset();
        observedProducer.setObservationEnabled(true);
        observedProducer.setObservationRegistry(observationRegistry);

        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void listenerObservation_activeSpanWhenTraceparentControlHeaderPresent() throws Exception {
        Span producerSpan = tracer.nextSpan().name("insight-kafka-propagation-test").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(producerSpan)) {
            ProducerRecord<String, PriceUpdatedEvent> record =
                    new ProducerRecord<>(
                            "market-prices",
                            "TRACE",
                            new PriceUpdatedEvent("TRACE", new BigDecimal("42.00")));
            record.headers()
                    .add(
                            "traceparent",
                            TraceparentTestSupport.w3cTraceparent(producerSpan)
                                    .getBytes(StandardCharsets.UTF_8));
            observedProducer.send(record).get();

            await().atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> {
                                String latest =
                                        redisTemplate.opsForValue().get("market:latest:TRACE");
                                assertThat(latest).isEqualTo("42.00");
                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_TRACE_ID.get())
                                        .isNotNull();
                            });
        } finally {
            producerSpan.end();
        }
    }

    /**
     * Property 2 (consumer half): observation-injected {@code traceparent} continues the same
     * trace ID under a distinct consumer span. Does not hand-stamp the header.
     */
    @Test
    void injectedTraceparent_consumerContinuesSameTraceIdWithDistinctSpanId() throws Exception {
        observedProducer
                .send("market-prices", "TRACE", new PriceUpdatedEvent("TRACE", new BigDecimal("42.00")))
                .get();

        try (KafkaConsumer<String, byte[]> sniffer = wireSniffer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(sniffer, "market-prices");
            List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
            await().atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> {
                                sniffer.poll(Duration.ofMillis(500)).forEach(seen::add);
                                ConsumerRecord<String, byte[]> record =
                                        seen.stream()
                                                .filter(r -> "TRACE".equals(r.key()))
                                                .reduce((a, b) -> b)
                                                .orElse(null);
                                assertThat(record).isNotNull();
                                Header header = record.headers().lastHeader("traceparent");
                                assertThat(header)
                                        .as("W3C traceparent must be injected by observation, not the test")
                                        .isNotNull();
                                String traceparent = new String(header.value(), StandardCharsets.UTF_8);
                                String producerTraceId = TraceparentTestSupport.traceId(traceparent);
                                String producerSpanId = TraceparentTestSupport.spanId(traceparent);

                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_TRACE_ID.get())
                                        .as("consumer must continue the producer trace")
                                        .isEqualTo(producerTraceId);
                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_SPAN_ID.get())
                                        .as("consumer span must be distinct from producer-send span")
                                        .isNotNull()
                                        .isNotEqualTo(producerSpanId);
                            });
        }
    }

    private KafkaConsumer<String, byte[]> wireSniffer() {
        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        embeddedKafkaBroker.getBrokersAsString(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "insight-wire-sniffer-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        ByteArrayDeserializer.class));
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
