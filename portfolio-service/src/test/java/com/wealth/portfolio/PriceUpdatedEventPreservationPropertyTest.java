package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.portfolio.kafka.MalformedEventException;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import com.wealth.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * Property 2: Preservation — non-buggy price-update pipeline behavior is unchanged.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3</b>
 *
 * <p>Observation-first methodology: these properties encode the baseline behavior observed on the
 * green PLAINTEXT / full-JRE path before the jlink SASL fix. They must continue to pass on the
 * unfixed build (confirming what to preserve) and after the fix (confirming no regressions).
 *
 * <p>Wire encoding (3.4) is covered by {@link PriceUpdatedEventBackCompatTest} and
 * market-data {@code PriceUpdatedEventProducerWireContractTest}. Idempotency near millisecond
 * boundaries (3.5) and observation-without-exporter (3.6) are covered by
 * {@link PriceUpdatedEventPreservationPropertyIT} and {@link PriceUpdatedEventKafkaRoundTripIT}.
 */
class PriceUpdatedEventPreservationPropertyTest {

    private static final String TOPIC = "market-prices";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final JacksonJsonDeserializer<PriceUpdatedEvent> deserializer =
            configuredDeserializer();

    @Property(tries = 40)
    void p2_noReferenceInAnyWindow_rendersNullChange_notFabricatedZero(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 8) String ticker,
            @ForAll @BigRange(min = "1.00", max = "99999.99") BigDecimal currentPrice) {

        PortfolioAnalyticsService analytics = analyticsServiceWithNoReference(ticker, currentPrice);

        PortfolioAnalyticsDto result = analytics.getAnalytics(USER_ID);
        HoldingAnalyticsDto holding = result.holdings().getFirst();

        assertThat(holding.change24hPercent()).isNull();
        assertThat(holding.change24hAbsolute()).isNull();
        assertThat(holding.changeBasis()).isNull();
    }

    @Property(tries = 40)
    void p2_malformedEvent_throwsMalformedEventException(@ForAll("malformedEvents") PriceUpdatedEvent event) {
        MarketPriceProjectionService projectionService = mock(MarketPriceProjectionService.class);
        PriceUpdatedEventListener listener = new PriceUpdatedEventListener(projectionService);

        assertThatThrownBy(() -> listener.on(event)).isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(any());
    }

    @Property(tries = 40)
    void p2_oldShapeWire_deserializesNullEnrichment_andDelegatesToProjection(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 8) String ticker,
            @ForAll @BigRange(min = "0.01", max = "99999.99") BigDecimal price) {

        String json =
                "{\"ticker\":\"%s\",\"newPrice\":%s}"
                        .formatted(ticker, price.setScale(2, RoundingMode.HALF_UP));

        PriceUpdatedEvent event = deserializer.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8));

        assertThat(event.quoteCurrency()).isNull();
        assertThat(event.observedAt()).isNull();
        assertThat(event.previousReferencePrice()).isNull();
        assertThat(event.previousReferenceAt()).isNull();

        MarketPriceProjectionService projectionService = mock(MarketPriceProjectionService.class);
        PriceUpdatedEventListener listener = new PriceUpdatedEventListener(projectionService);
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
        verify(projectionService).upsertLatestPrice(event);
    }

    @Provide
    Arbitrary<PriceUpdatedEvent> malformedEvents() {
        Arbitrary<String> badTickers =
                Arbitraries.oneOf(
                        Arbitraries.just(null),
                        Arbitraries.just(""),
                        Arbitraries.just("   "));
        Arbitrary<BigDecimal> badPrices =
                Arbitraries.oneOf(
                        Arbitraries.just(null),
                        Arbitraries.just(BigDecimal.ZERO),
                        Arbitraries.bigDecimals().between(new BigDecimal("-9999"), new BigDecimal("-0.01")));

        return Combinators.combine(badTickers, badPrices)
                .as(PriceUpdatedEvent::new);
    }

    private static JacksonJsonDeserializer<PriceUpdatedEvent> configuredDeserializer() {
        JacksonJsonDeserializer<PriceUpdatedEvent> d = new JacksonJsonDeserializer<>(PriceUpdatedEvent.class);
        d.addTrustedPackages("*");
        return d;
    }

    private PortfolioAnalyticsService analyticsServiceWithNoReference(String ticker, BigDecimal currentPrice) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserRepository userRepository = mock(UserRepository.class);
        PortfolioRepository portfolioRepository = mock(PortfolioRepository.class);
        FxProperties fxProperties = mock(FxProperties.class);
        SeedTickerRegistry seedTickerRegistry = mock(SeedTickerRegistry.class);

        when(fxProperties.baseCurrency()).thenReturn("USD");
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
        when(portfolioRepository.existsByUserId(USER_ID)).thenReturn(true);
        when(seedTickerRegistry.find(anyString())).thenReturn(Optional.empty());

        AnalyticsQueryRow row =
                new AnalyticsQueryRow(
                        "HOLDING",
                        ticker,
                        BigDecimal.ONE,
                        currentPrice,
                        "USD",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of(row));

        return new PortfolioAnalyticsService(
                jdbcTemplate,
                userRepository,
                portfolioRepository,
                mock(FxRateProvider.class),
                fxProperties,
                seedTickerRegistry);
    }
}
