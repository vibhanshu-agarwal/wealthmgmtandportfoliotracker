package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer wire contract test for {@link PriceUpdatedEvent} (Task 6.5).
 *
 * <p>Uses a no-arg {@link JacksonJsonSerializer} — the same instantiation path as production
 * ({@code spring.kafka.producer.value-serializer} class-name config with no custom
 * {@code ProducerFactory} / {@code DefaultKafkaProducerFactoryCustomizer}). Spring Kafka builds
 * its own default Jackson 3 mapper inside the serializer; it is <strong>not</strong> the Spring
 * Boot {@code JsonMapper} bean (that bean backs HTTP/MVC boundaries, tasks 6.3/6.6).
 *
 * <p>This test pins the expected on-wire JSON body shape. True producer→consumer fidelity
 * (including type headers and DLT routing) is deferred to Task 6.7 (Testcontainers).
 *
 * <p>Temporal encoding contract (coordinated with portfolio consumer Task 6.2):
 * {@code observedAt} and {@code previousReferenceAt} are emitted as ISO-8601 UTC strings.
 */
class PriceUpdatedEventProducerWireContractTest {

    private static final String TOPIC = "market-prices";

    /**
     * Frozen enriched event values — same instants/prices as the consumer-side ISO-8601 fixture
     * in {@code portfolio-service} Task 6.2 tests.
     */
    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T10:15:30Z");
    private static final Instant PREVIOUS_REFERENCE_AT = Instant.parse("2026-06-07T10:15:30Z");

    private JacksonJsonSerializer<PriceUpdatedEvent> serializer;

    @BeforeEach
    void setUp() {
        serializer = new JacksonJsonSerializer<>();
    }

    @AfterEach
    void tearDown() {
        serializer.close();
    }

    @Test
    void enrichedEvent_serializesTemporalFieldsAsIso8601() {
        PriceUpdatedEvent event = new PriceUpdatedEvent(
                "BTC-USD",
                new BigDecimal("64000.50"),
                "USD",
                OBSERVED_AT,
                new BigDecimal("63250.00"),
                PREVIOUS_REFERENCE_AT);

        byte[] wire = serializer.serialize(TOPIC, event);
        String json = new String(wire, StandardCharsets.UTF_8);

        assertThat(json).contains("\"observedAt\":\"2026-06-08T10:15:30Z\"");
        assertThat(json).contains("\"previousReferenceAt\":\"2026-06-07T10:15:30Z\"");
    }

    @Test
    void enrichedEvent_serializesEnrichmentFields() {
        PriceUpdatedEvent event = new PriceUpdatedEvent(
                "BTC-USD",
                new BigDecimal("64000.50"),
                "USD",
                OBSERVED_AT,
                new BigDecimal("63250.00"),
                PREVIOUS_REFERENCE_AT);

        byte[] wire = serializer.serialize(TOPIC, event);
        String json = new String(wire, StandardCharsets.UTF_8);

        assertThat(json).contains("\"ticker\":\"BTC-USD\"");
        assertThat(json).contains("\"quoteCurrency\":\"USD\"");
        assertThat(json).contains("\"newPrice\":64000.50");
        assertThat(json).contains("\"previousReferencePrice\":63250.00");
    }

    @Test
    void legacyTwoArgEvent_omitsEnrichmentTemporalFields() throws Exception {
        PriceUpdatedEvent event = new PriceUpdatedEvent("TSLA", new BigDecimal("250.00"));

        byte[] wire = serializer.serialize(TOPIC, event);
        var tree = JsonMapper.builder().build().readTree(wire);

        assertThat(tree.get("ticker").asString()).isEqualTo("TSLA");
        assertThat(tree.get("newPrice").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(tree.hasNonNull("observedAt")).isFalse();
        assertThat(tree.hasNonNull("previousReferenceAt")).isFalse();
    }
}
