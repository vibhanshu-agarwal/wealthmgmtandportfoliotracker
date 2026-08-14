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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
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
 *
 * <p>Property 3: a malformed or absent {@code traceparent} starts a new valid trace and the
 * message is still processed. Those cases send without Kafka observation so a valid header is
 * not injected.
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
    private static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");

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

    private KafkaTemplate<String, PriceUpdatedEvent> nonObservedProducer;

    @BeforeEach
    void setUp() {
        InsightKafkaTracePropagationProbe.reset();
        observedProducer.setObservationEnabled(true);
        observedProducer.setObservationRegistry(observationRegistry);

        Map<String, Object> nonObservedProps = new HashMap<>();
        nonObservedProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBrokersAsString());
        nonObservedProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);
        ProducerFactory<String, PriceUpdatedEvent> nonObservedFactory =
                new DefaultKafkaProducerFactory<>(
                        nonObservedProps, new StringSerializer(), new JacksonJsonSerializer<>());
        nonObservedProducer = new KafkaTemplate<>(nonObservedFactory);
        nonObservedProducer.setObservationEnabled(false);

        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        if (nonObservedProducer != null) {
            nonObservedProducer.destroy();
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

    /**
     * Property 3: a missing {@code traceparent} starts a new valid W3C trace and the
     * well-formed event is still processed.
     */
    @Test
    void absentTraceparent_startsNewValidTraceAndProcessesMessage() throws Exception {
        sendWithoutObservation(
                new ProducerRecord<>(
                        "market-prices",
                        "TRACE",
                        new PriceUpdatedEvent("TRACE", new BigDecimal("43.00"))));

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
                                assertThat(record.headers().lastHeader("traceparent"))
                                        .as("absent-traceparent send must not inject a valid header")
                                        .isNull();

                                assertThat(redisTemplate.opsForValue().get("market:latest:TRACE"))
                                        .as("message must still be processed")
                                        .isEqualTo("43.00");

                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_TRACE_ID.get())
                                        .as("consumer must start a new valid W3C trace")
                                        .matches("[0-9a-f]{32}");
                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_SPAN_ID.get())
                                        .as("consumer span id must be valid W3C")
                                        .matches("[0-9a-f]{16}");
                            });
        }
    }

    /**
     * Property 3: a malformed {@code traceparent} starts a new valid W3C trace and the
     * well-formed event is still processed.
     */
    @Test
    void malformedTraceparent_startsNewValidTraceAndProcessesMessage() throws Exception {
        String garbage = "not-a-traceparent";
        ProducerRecord<String, PriceUpdatedEvent> outbound =
                new ProducerRecord<>(
                        "market-prices",
                        "TRACE",
                        new PriceUpdatedEvent("TRACE", new BigDecimal("44.00")));
        outbound.headers().add("traceparent", garbage.getBytes(StandardCharsets.UTF_8));
        sendWithoutObservation(outbound);

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
                                assertThat(header).isNotNull();
                                assertThat(new String(header.value(), StandardCharsets.UTF_8))
                                        .as("malformed header must not be overwritten by observation")
                                        .isEqualTo(garbage);

                                assertThat(redisTemplate.opsForValue().get("market:latest:TRACE"))
                                        .as("message must still be processed")
                                        .isEqualTo("44.00");

                                String consumerTraceId =
                                        InsightKafkaTracePropagationProbe.CONSUMER_TRACE_ID.get();
                                assertThat(consumerTraceId)
                                        .as("consumer must start a new valid W3C trace")
                                        .matches("[0-9a-f]{32}");
                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_SPAN_ID.get())
                                        .as("consumer span id must be valid W3C")
                                        .matches("[0-9a-f]{16}");
                                var fragment = HEX_32.matcher(garbage);
                                while (fragment.find()) {
                                    assertThat(consumerTraceId)
                                            .as(
                                                    "new trace must not reuse a 32-hex fragment of the garbage header")
                                            .isNotEqualTo(fragment.group());
                                }
                            });
        }
    }

    /**
     * Send on a path that does not run Kafka observation, so a missing/malformed
     * {@code traceparent} is not replaced by a valid injected header.
     */
    private void sendWithoutObservation(ProducerRecord<String, PriceUpdatedEvent> record)
            throws Exception {
        nonObservedProducer.send(record).get();
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
