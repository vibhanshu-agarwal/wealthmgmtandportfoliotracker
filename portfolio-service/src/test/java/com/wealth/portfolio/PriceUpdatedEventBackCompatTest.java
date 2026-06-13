package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Back-compat (de)serialization tests for {@link PriceUpdatedEvent} (Task 1.2, Property 9).
 *
 * <p>These exercise the <em>production</em> Kafka wire path: portfolio-service consumes via
 * Spring Kafka's {@link JacksonJsonDeserializer} (configured exactly as in
 * {@code PortfolioKafkaConfig} — trusted packages {@code *}, target type
 * {@link PriceUpdatedEvent}). The contract module {@code common-dto} has no Jackson on its
 * classpath, so co-locating the round-trip test with a real consumer is the representative
 * place to assert the wire contract.
 *
 * <p>Property 9: any valid old-shape event (only {@code ticker} + {@code newPrice})
 * deserializes successfully with the new fields resolving to {@code null}, and is never
 * rejected solely for missing — or unknown — new fields.
 */
class PriceUpdatedEventBackCompatTest {

    private static final String TOPIC = "price-updated";

    /**
     * Frozen enriched wire payload (Task 6.2). Temporal fields are ISO-8601 strings — the
     * shape the consumer must tolerate; producer emission is pinned in market-data Task 6.5.
     */
    private static final String ENRICHED_ISO8601_FIXTURE = """
            {"ticker":"BTC-USD","newPrice":64000.50,"quoteCurrency":"USD",\
            "observedAt":"2026-06-08T10:15:30Z","previousReferencePrice":63250.00,\
            "previousReferenceAt":"2026-06-07T10:15:30Z"}""";

    private JacksonJsonDeserializer<PriceUpdatedEvent> deserializer;
    private JacksonJsonSerializer<PriceUpdatedEvent> serializer;

    @BeforeEach
    void setUp() {
        deserializer = new JacksonJsonDeserializer<>(PriceUpdatedEvent.class);
        deserializer.addTrustedPackages("*");
        serializer = new JacksonJsonSerializer<>();
    }

    @AfterEach
    void tearDown() {
        deserializer.close();
        serializer.close();
    }

    private PriceUpdatedEvent deserialize(String json) {
        return deserializer.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oldShapeJson_deserializes_withNewFieldsNull() {
        String oldShape = "{\"ticker\":\"AAPL\",\"newPrice\":150.00}";

        PriceUpdatedEvent event = deserialize(oldShape);

        assertThat(event.ticker()).isEqualTo("AAPL");
        assertThat(event.newPrice()).isEqualByComparingTo("150.00");
        assertThat(event.quoteCurrency()).isNull();
        assertThat(event.observedAt()).isNull();
        assertThat(event.previousReferencePrice()).isNull();
        assertThat(event.previousReferenceAt()).isNull();
    }

    @Test
    void oldShapeJson_doesNotFail_forMissingNewFields() {
        String oldShape = "{\"ticker\":\"MSFT\",\"newPrice\":420.00}";

        assertThatCode(() -> deserialize(oldShape)).doesNotThrowAnyException();
    }

    @Test
    void unknownProperty_doesNotFail() {
        // A forward-compatible producer may add fields this consumer does not yet know about.
        // Property 9 requires such events not be rejected (no FAIL_ON_UNKNOWN_PROPERTIES failure).
        String withUnknown =
                "{\"ticker\":\"AAPL\",\"newPrice\":150.00,\"someFutureField\":\"ignored\"}";

        PriceUpdatedEvent event = deserialize(withUnknown);

        assertThat(event.ticker()).isEqualTo("AAPL");
        assertThat(event.newPrice()).isEqualByComparingTo("150.00");
    }

    @Test
    void newShapeJson_roundTrips_withAllFieldsPreserved() {
        PriceUpdatedEvent original = new PriceUpdatedEvent(
                "BTC-USD",
                new BigDecimal("64000.50"),
                "USD",
                Instant.parse("2026-06-08T10:15:30Z"),
                new BigDecimal("63250.00"),
                Instant.parse("2026-06-07T10:15:30Z"));

        byte[] wire = serializer.serialize(TOPIC, original);
        PriceUpdatedEvent restored = deserializer.deserialize(TOPIC, wire);

        assertThat(restored.ticker()).isEqualTo("BTC-USD");
        assertThat(restored.newPrice()).isEqualByComparingTo("64000.50");
        assertThat(restored.quoteCurrency()).isEqualTo("USD");
        assertThat(restored.observedAt()).isEqualTo(Instant.parse("2026-06-08T10:15:30Z"));
        assertThat(restored.previousReferencePrice()).isEqualByComparingTo("63250.00");
        assertThat(restored.previousReferenceAt()).isEqualTo(Instant.parse("2026-06-07T10:15:30Z"));
    }

    @Test
    void legacyTwoArgEvent_roundTrips_withNewFieldsNull() {
        // The 2-arg constructor is what every existing producer call site uses; ensure an event
        // built that way serializes and deserializes back to an equivalent value.
        PriceUpdatedEvent original = new PriceUpdatedEvent("TSLA", new BigDecimal("250.00"));

        byte[] wire = serializer.serialize(TOPIC, original);
        PriceUpdatedEvent restored = deserializer.deserialize(TOPIC, wire);

        assertThat(restored.ticker()).isEqualTo("TSLA");
        assertThat(restored.newPrice()).isEqualByComparingTo("250.00");
        assertThat(restored.quoteCurrency()).isNull();
        assertThat(restored.observedAt()).isNull();
        assertThat(restored.previousReferencePrice()).isNull();
        assertThat(restored.previousReferenceAt()).isNull();
    }

    @Test
    void frozenIso8601Fixture_deserializes_withEnrichmentFields() {
        PriceUpdatedEvent event = deserialize(ENRICHED_ISO8601_FIXTURE);

        assertThat(event.ticker()).isEqualTo("BTC-USD");
        assertThat(event.newPrice()).isEqualByComparingTo("64000.50");
        assertThat(event.quoteCurrency()).isEqualTo("USD");
        assertThat(event.observedAt()).isEqualTo(Instant.parse("2026-06-08T10:15:30Z"));
        assertThat(event.previousReferencePrice()).isEqualByComparingTo("63250.00");
        assertThat(event.previousReferenceAt()).isEqualTo(Instant.parse("2026-06-07T10:15:30Z"));
    }

    @Test
    void malformedJson_throwsOnDeserialization() {
        assertThatThrownBy(() -> deserialize("{not valid json"))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void nonNumericNewPrice_throwsOnDeserialization() {
        assertThatThrownBy(() -> deserialize("{\"ticker\":\"AAPL\",\"newPrice\":\"oops\"}"))
                .isInstanceOf(SerializationException.class);
    }
}
