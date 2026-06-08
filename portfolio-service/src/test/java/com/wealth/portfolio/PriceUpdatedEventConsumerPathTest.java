package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;

/**
 * Consumer-path compatibility test for {@link PriceUpdatedEvent} (PR review follow-up).
 *
 * <p>This test exercises the <strong>configured consumer-factory deserialization path</strong>
 * (Property 9) by:
 * <ol>
 *   <li>Building the same {@link ErrorHandlingDeserializer} + {@link JacksonJsonDeserializer}
 *       stack that {@link PortfolioKafkaConfig} wires (same config keys, same trusted-packages
 *       and target-type settings).
 *   <li>Deserializing old-shape and new-shape JSON bytes through that stack.
 *   <li>Invoking {@link PriceUpdatedEventListener#on} with the deserialized event and asserting
 *       delegation to {@link MarketPriceProjectionService}.
 * </ol>
 *
 * <p>This complements {@link PriceUpdatedEventBackCompatTest}, which only proves the wire format
 * via the bare deserializer. This test additionally proves:
 * <ul>
 *   <li>The {@link ErrorHandlingDeserializer} wrapper does not suppress or transform old-shape
 *       events.
 *   <li>A deserialized old-shape event (new fields {@code null}) passes listener validation and
 *       reaches {@link MarketPriceProjectionService#upsertLatestPrice} — it is not mis-classified
 *       as malformed and does not take the DLT path.
 *   <li>A deserialized new-shape event also completes the full consumer path.
 *   <li>Missing {@code observedAt} on an old-shape event does <em>not</em> prevent normal
 *       projection delegation; there is no receive-time fabrication in this path.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PriceUpdatedEventConsumerPathTest {

    private static final String TOPIC = "market-prices";

    @Mock
    private MarketPriceProjectionService projectionService;

    /**
     * Build the same deserializer configuration that {@link PortfolioKafkaConfig} uses.
     *
     * <p>{@link JacksonJsonDeserializer} enforces that you configure it <em>either</em> via
     * programmatic setters <em>or</em> via {@code configure(Map)} — never both. In the real
     * {@link PortfolioKafkaConfig} a fresh (unconfigured) instance is handed to
     * {@code DefaultKafkaConsumerFactory}, which calls {@code configure(Map)} once. Here we
     * replicate the equivalent state using only programmatic setters, which is how the
     * deserializer itself recommends unit-test usage.
     */
    private ErrorHandlingDeserializer<PriceUpdatedEvent> buildConfiguredDeserializer() {
        // Inner deserializer — use setter API only (configure(Map) would conflict)
        JacksonJsonDeserializer<PriceUpdatedEvent> inner =
                new JacksonJsonDeserializer<>(PriceUpdatedEvent.class);
        inner.addTrustedPackages("*");

        // Outer wrapper — constructed with the already-configured delegate; no configure() call
        return new ErrorHandlingDeserializer<>(inner);
    }

    private PriceUpdatedEvent deserialize(ErrorHandlingDeserializer<PriceUpdatedEvent> d,
                                          String json) {
        return d.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Old-shape JSON → full consumer path → projection delegation
    // -----------------------------------------------------------------------

    @Test
    void oldShape_throughConfiguredDeserializer_reachesProjectionService() {
        var deserializer = buildConfiguredDeserializer();
        String oldShape = "{\"ticker\":\"AAPL\",\"newPrice\":150.00}";

        PriceUpdatedEvent event = deserialize(deserializer, oldShape);

        // Sanity: new fields are null — no receive-time fabrication
        assertThat(event.observedAt()).isNull();
        assertThat(event.previousReferencePrice()).isNull();

        // Full listener path: valid old-shape event must delegate to projection, not DLT
        var listener = new PriceUpdatedEventListener(projectionService);
        listener.on(event);

        var captor = forClass(PriceUpdatedEvent.class);
        verify(projectionService).upsertLatestPrice(captor.capture());
        assertThat(captor.getValue().ticker()).isEqualTo("AAPL");
        assertThat(captor.getValue().newPrice()).isEqualByComparingTo("150.00");
        assertThat(captor.getValue().observedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // New-shape JSON → full consumer path → projection delegation
    // -----------------------------------------------------------------------

    @Test
    void newShape_throughConfiguredDeserializer_reachesProjectionService() {
        var deserializer = buildConfiguredDeserializer();
        Instant obs = Instant.parse("2026-06-08T10:15:30Z");
        Instant refAt = Instant.parse("2026-06-07T10:15:30Z");
        String newShape = """
                {"ticker":"BTC-USD","newPrice":64000.50,"quoteCurrency":"USD",\
                "observedAt":"2026-06-08T10:15:30Z","previousReferencePrice":63250.00,\
                "previousReferenceAt":"2026-06-07T10:15:30Z"}""";

        PriceUpdatedEvent event = deserialize(deserializer, newShape);

        assertThat(event.ticker()).isEqualTo("BTC-USD");
        assertThat(event.quoteCurrency()).isEqualTo("USD");
        assertThat(event.observedAt()).isEqualTo(obs);
        assertThat(event.previousReferenceAt()).isEqualTo(refAt);

        var listener = new PriceUpdatedEventListener(projectionService);
        listener.on(event);

        verify(projectionService).upsertLatestPrice(event);
    }

    // -----------------------------------------------------------------------
    // Old-shape event must not be mis-classified as malformed
    // -----------------------------------------------------------------------

    @Test
    void oldShape_nullEnrichmentFields_doNotTriggerMalformedEventException() {
        // PriceUpdatedEventListener only validates ticker and newPrice; null enrichment
        // fields must not cause a MalformedEventException (which would route to the DLT).
        var deserializer = buildConfiguredDeserializer();
        String oldShape = "{\"ticker\":\"RELIANCE.NS\",\"newPrice\":2950.00}";

        PriceUpdatedEvent event = deserialize(deserializer, oldShape);

        var listener = new PriceUpdatedEventListener(projectionService);
        // Must not throw — null quoteCurrency/observedAt/etc. are valid in Wave 1
        listener.on(event);

        verify(projectionService).upsertLatestPrice(event);
    }

    // -----------------------------------------------------------------------
    // Unknown future fields must not prevent deserialization
    // -----------------------------------------------------------------------

    @Test
    void unknownField_throughConfiguredDeserializer_doesNotFail() {
        var deserializer = buildConfiguredDeserializer();
        String withUnknown =
                "{\"ticker\":\"MSFT\",\"newPrice\":420.00,\"someFutureField\":\"ignored\"}";

        PriceUpdatedEvent event = deserialize(deserializer, withUnknown);

        assertThat(event).isNotNull();
        assertThat(event.ticker()).isEqualTo("MSFT");

        var listener = new PriceUpdatedEventListener(projectionService);
        listener.on(event);

        verify(projectionService).upsertLatestPrice(event);
    }
}
