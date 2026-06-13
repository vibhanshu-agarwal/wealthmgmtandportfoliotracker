package com.wealth.market.events;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Parameterized Jackson 3 round-trip tests for {@link PriceUpdatedEvent} (Task 4.2).
 *
 * <p><b>Property 3: Event contract preserved</b> — asserts serialize→deserialize equality
 * against the configured {@link tools.jackson.databind.json.JsonMapper}, not an ad-hoc mapper.
 */
class PriceUpdatedEventJackson3RoundTripTest {

    private final JsonMapper mapper = ContractJsonMapper.instance();

    @ParameterizedTest(name = "{0}")
    @MethodSource("roundTripEvents")
    void roundTrip_preservesEvent(String caseName, PriceUpdatedEvent original) throws Exception {
        String json = mapper.writeValueAsString(original);
        PriceUpdatedEvent restored = mapper.readValue(json, PriceUpdatedEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    static Stream<Arguments> roundTripEvents() {
        return Stream.of(
                Arguments.of(
                        "legacy two-arg shape",
                        new PriceUpdatedEvent("TSLA", new BigDecimal("250.00"))),
                Arguments.of(
                        "enriched six-field shape",
                        new PriceUpdatedEvent(
                                "BTC-USD",
                                new BigDecimal("64000.50"),
                                "USD",
                                Instant.parse("2026-06-08T10:15:30Z"),
                                new BigDecimal("63250.00"),
                                Instant.parse("2026-06-07T10:15:30Z"))),
                Arguments.of(
                        "partial enrichment",
                        new PriceUpdatedEvent(
                                "AAPL",
                                new BigDecimal("178.50"),
                                "USD",
                                Instant.parse("2026-06-08T12:00:00Z"),
                                null,
                                null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyWireJson")
    void legacyJson_deserializes_withNewFieldsNull(String caseName, String json) throws Exception {
        PriceUpdatedEvent event = mapper.readValue(json, PriceUpdatedEvent.class);

        assertThat(event.ticker()).isEqualTo(caseName);
        assertThat(event.newPrice()).isNotNull();
        assertThat(event.quoteCurrency()).isNull();
        assertThat(event.observedAt()).isNull();
        assertThat(event.previousReferencePrice()).isNull();
        assertThat(event.previousReferenceAt()).isNull();
    }

    static Stream<Arguments> legacyWireJson() {
        return Stream.of(
                Arguments.of("AAPL", "{\"ticker\":\"AAPL\",\"newPrice\":150.00}"),
                Arguments.of("MSFT", "{\"ticker\":\"MSFT\",\"newPrice\":420.00}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forwardCompatibleJson")
    void forwardCompatibleJson_doesNotFail(String caseName, String json) {
        assertThatCode(() -> mapper.readValue(json, PriceUpdatedEvent.class))
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> forwardCompatibleJson() {
        return Stream.of(
                Arguments.of(
                        "unknown property tolerated",
                        "{\"ticker\":\"AAPL\",\"newPrice\":150.00,\"someFutureField\":\"ignored\"}"),
                Arguments.of(
                        "missing enrichment fields tolerated",
                        "{\"ticker\":\"NVDA\",\"newPrice\":900.00}"));
    }
}
