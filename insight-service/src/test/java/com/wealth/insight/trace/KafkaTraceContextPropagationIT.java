package com.wealth.insight.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.wealth.insight.TestContainerImages;
import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
 * <p>Asserts {@link Tracer#currentSpan()} at consume time (not production {@code KafkaTemplate}
 * injection). Trace-ID continuity is deferred — see migration spec task 11.2 partial note.
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
@Import(InsightKafkaTracePropagationProbe.class)
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
}
