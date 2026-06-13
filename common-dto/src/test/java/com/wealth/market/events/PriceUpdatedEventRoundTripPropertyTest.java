package com.wealth.market.events;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.constraints.StringLength;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jqwik property test for {@link PriceUpdatedEvent} Jackson 3 round-trip (Task 4.3).
 *
 * <p><b>Property 3: Event contract preserved</b> — {@code ∀ e: deserialize(serialize(e)) == e}
 * under the configured {@link tools.jackson.databind.json.JsonMapper}.
 */
class PriceUpdatedEventRoundTripPropertyTest {

    private final JsonMapper mapper = ContractJsonMapper.instance();

    @Property(tries = 100)
    void roundTrip_preservesEvent(
            @ForAll("priceUpdatedEvents") PriceUpdatedEvent original) throws Exception {
        String json = mapper.writeValueAsString(original);
        PriceUpdatedEvent restored = mapper.readValue(json, PriceUpdatedEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Provide
    Arbitrary<PriceUpdatedEvent> priceUpdatedEvents() {
        Arbitrary<String> tickers = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12)
                .map(String::toUpperCase);

        Arbitrary<BigDecimal> prices = Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(1_000_000))
                .ofScale(2);

        Arbitrary<String> quoteCurrencies = Arbitraries.of("USD", "EUR", "GBP").injectNull(0.25);
        Arbitrary<Instant> instants = Arbitraries.longs()
                .between(
                        Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(),
                        Instant.parse("2030-12-31T23:59:59Z").toEpochMilli())
                .map(Instant::ofEpochMilli)
                .injectNull(0.25);
        Arbitrary<BigDecimal> referencePrices = prices.injectNull(0.25);
        // Reuse the same instant generator — observedAt and previousReferenceAt share millisecond precision.
        Arbitrary<Instant> referenceAts = instants;

        return Combinators.combine(tickers, prices, quoteCurrencies, instants, referencePrices, referenceAts)
                .as(PriceUpdatedEvent::new);
    }

    @Property(tries = 50)
    void legacyTwoArgShape_roundTrips(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 12) String ticker,
            @ForAll @Positive BigDecimal newPrice) throws Exception {
        PriceUpdatedEvent original = new PriceUpdatedEvent(ticker.toUpperCase(), newPrice);

        String json = mapper.writeValueAsString(original);
        PriceUpdatedEvent restored = mapper.readValue(json, PriceUpdatedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
