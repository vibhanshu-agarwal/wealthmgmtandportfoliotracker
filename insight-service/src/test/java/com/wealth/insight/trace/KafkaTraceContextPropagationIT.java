package com.wealth.insight.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Property 10b (insight consumer): producer→consumer {@code traceparent} continuity on
 * {@link PriceUpdatedEvent} — the consumer continues the same trace (no new root span).
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
            "spring.kafka.template.observation-enabled=true"
        })
@EmbeddedKafka(partitions = 1, topics = "market-prices")
@Import(InsightKafkaTracePropagationProbe.class)
@TestPropertySource(
        properties = {
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false",
            "management.tracing.sampling.probability=1.0",
            "spring.kafka.listener.observation-enabled=true"
        })
class KafkaTraceContextPropagationIT {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
    }

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired private Tracer tracer;

    @Autowired private ObservationRegistry observationRegistry;

    private KafkaTemplate<String, PriceUpdatedEvent> observedProducer;

    @BeforeEach
    void setUp() {
        InsightKafkaTracePropagationProbe.reset();
        TraceparentProducerInterceptor.clearTraceparent();
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TraceparentProducerInterceptor.class.getName());
        var producerFactory =
                new DefaultKafkaProducerFactory<String, PriceUpdatedEvent>(
                        producerProps,
                        new org.apache.kafka.common.serialization.StringSerializer(),
                        new JacksonJsonSerializer<>());
        observedProducer = new KafkaTemplate<>(producerFactory);
        observedProducer.setObservationRegistry(observationRegistry);
        observedProducer.setObservationEnabled(true);

        var keys = redisTemplate.keys("market:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void observedProducerToInsightConsumer_preservesTraceId() throws Exception {
        Span producerSpan = tracer.nextSpan().name("insight-kafka-propagation-test").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(producerSpan)) {
            String expectedTraceId = producerSpan.context().traceId();
            String traceparent =
                    "00-%s-%s-%02x"
                            .formatted(
                                    producerSpan.context().traceId(),
                                    producerSpan.context().spanId(),
                                    Boolean.TRUE.equals(producerSpan.context().sampled()) ? 0x01 : 0x00);
            assertThat(traceparent).startsWith("00-");
            TraceparentProducerInterceptor.seedTraceparent(traceparent);
            observedProducer
                    .send("market-prices", "TRACE", new PriceUpdatedEvent("TRACE", new BigDecimal("42.00")))
                    .get();

            await().atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> {
                                String latest =
                                        redisTemplate.opsForValue().get("market:latest:TRACE");
                                assertThat(latest).isEqualTo("42.00");
                                assertThat(InsightKafkaTracePropagationProbe.CONSUMER_TRACE_ID.get())
                                        .isEqualToIgnoringCase(expectedTraceId);
                            });
        } finally {
            producerSpan.end();
        }
    }
}
