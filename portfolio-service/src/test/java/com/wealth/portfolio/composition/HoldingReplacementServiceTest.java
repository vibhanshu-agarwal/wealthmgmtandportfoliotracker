package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldingReplacementServiceTest {

    private static final Instant GOLDEN_ANCHOR = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant PORTFOLIO_CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PORTFOLIO_UPDATED = Instant.parse("2026-01-02T00:00:00Z");

    @Mock PortfolioRepository portfolioRepository;
    @Mock CompositionCatalogValidator catalogValidator;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock EntityManager entityManager;
    @Mock Clock clock;
    @Mock TuplePreparer preparer;
    @Mock AddTimeCostBasisCapturer costBasisCapturer;

    HoldingReplacementService service;
    CompositionTuplePreparer compositionPreparer;
    GoldenStateTuplePreparer goldenPreparer;
    String activeTicker;
    SeedTickerRegistry seedRegistry;

    @BeforeEach
    void setUp() {
        service =
                new HoldingReplacementService(
                        portfolioRepository,
                        catalogValidator,
                        jdbcTemplate,
                        entityManager,
                        clock);
        compositionPreparer = new CompositionTuplePreparer(costBasisCapturer);
        SupportedCatalog catalog = SupportedCatalog.load();
        seedRegistry = new SeedTickerRegistry(catalog, catalog.seedView());
        activeTicker = seedRegistry.active().getFirst().ticker();
        goldenPreparer = new GoldenStateTuplePreparer(seedRegistry, "u1", GOLDEN_ANCHOR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("p1Matrix")
    void p1FourCaseMatrixOnBothWriters(
            String ignored,
            WriterMode mode,
            long expectedVersion,
            boolean desiredDiffers,
            boolean expectConflict,
            boolean expectNoOp) {
        Portfolio portfolio = presentPortfolio(5L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        TuplePreparer realWriter =
                mode == WriterMode.COMPOSITION ? compositionPreparer : goldenPreparer;
        List<RawIntent> intent;
        if (mode == WriterMode.COMPOSITION) {
            if (desiredDiffers) {
                if (!expectConflict) {
                    when(costBasisCapturer.captureNew(eq(activeTicker), any()))
                            .thenAnswer(
                                    inv ->
                                            new DesiredHoldingState(
                                                    inv.getArgument(0),
                                                    inv.getArgument(1),
                                                    null,
                                                    null,
                                                    null,
                                                    null));
                }
                intent = List.of(new RawIntent(activeTicker, new BigDecimal("9.00000000")));
            } else {
                intent = List.of();
            }
        } else {
            // Golden equal: locked already holds the golden tuple for the first active ticker.
            // Golden differs: empty locked vs non-empty golden materialisation from empty intent.
            // Setup uses the unwrapped preparer so the counting delegate only observes replace().
            if (!desiredDiffers) {
                DesiredHoldingState golden =
                        goldenPreparer
                                .materialise(
                                        List.of(
                                                new RawIntent(
                                                        activeTicker, quantityFor(activeTicker))),
                                        List.of())
                                .getFirst();
                portfolio.addHolding(
                        holdingFrom(
                                portfolio,
                                golden.ticker(),
                                golden.quantity(),
                                golden.avgCostBasis(),
                                golden.costBasisCurrency(),
                                golden.costBasisSource(),
                                golden.costBasisAsOf()));
                intent =
                        List.of(
                                new RawIntent(
                                        activeTicker,
                                        QuantityDomain.canonicalQuantity(golden.quantity())));
            } else {
                intent = List.of();
            }
        }

        AtomicInteger materialiseCalls = new AtomicInteger();
        TuplePreparer writer = counting(realWriter, materialiseCalls);

        if (expectConflict) {
            assertThatThrownBy(() -> service.replace("u1", expectedVersion, intent, writer))
                    .isInstanceOf(PortfolioVersionConflictException.class)
                    .satisfies(
                            ex ->
                                    assertThat(
                                                    ((PortfolioVersionConflictException) ex)
                                                            .currentVersion())
                                            .hasValue(5L));
            assertThat(materialiseCalls.get()).isZero();
            verify(catalogValidator, never()).validate(anyList(), anyList());
            verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
            verify(portfolioRepository, never()).saveAndFlush(any());
            verify(entityManager, never()).flush();
            verify(entityManager, never()).refresh(any());
            verify(costBasisCapturer, never()).captureNew(anyString(), any());
            return;
        }

        if (desiredDiffers) {
            when(clock.instant()).thenReturn(Instant.parse("2026-06-01T12:00:00Z"));
            when(jdbcTemplate.update(anyString(), any(), any(), anyLong())).thenReturn(1);
        }

        CompositionResult result = service.replace("u1", expectedVersion, intent, writer);

        assertThat(materialiseCalls.get()).isEqualTo(1);
        if (expectNoOp) {
            assertThat(result.noOp()).isTrue();
            assertThat(result.version()).isEqualTo(5L);
            verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
        } else {
            assertThat(result.noOp()).isFalse();
            verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), anyLong());
        }
        verify(catalogValidator).validate(anyList(), anyList());
    }

    @ParameterizedTest(name = "{0} stale + invalid quantity → 409 before materialise")
    @MethodSource("writerModes")
    void staleInvalidSemanticDoesNotReachRealPreparer(WriterMode mode) {
        Portfolio portfolio = presentPortfolio(5L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        AtomicInteger materialiseCalls = new AtomicInteger();
        TuplePreparer writer =
                counting(
                        mode == WriterMode.COMPOSITION ? compositionPreparer : goldenPreparer,
                        materialiseCalls);

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        4L,
                                        List.of(new RawIntent(activeTicker, BigDecimal.ZERO)),
                                        writer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(5L));

        assertThat(materialiseCalls.get()).isZero();
        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
        verify(costBasisCapturer, never()).captureNew(anyString(), any());
    }

    static Stream<Arguments> writerModes() {
        return Stream.of(
                Arguments.of(Named.of("composition", WriterMode.COMPOSITION)),
                Arguments.of(Named.of("golden", WriterMode.GOLDEN)));
    }

    static Stream<Arguments> p1Matrix() {
        return Stream.of(
                Arguments.of(
                        Named.of("composition match equal → no-op", "c-eq"),
                        WriterMode.COMPOSITION,
                        5L,
                        false,
                        false,
                        true),
                Arguments.of(
                        Named.of("composition match differs → transition", "c-diff"),
                        WriterMode.COMPOSITION,
                        5L,
                        true,
                        false,
                        false),
                Arguments.of(
                        Named.of("composition stale equal → 409 before write", "c-stale-eq"),
                        WriterMode.COMPOSITION,
                        4L,
                        false,
                        true,
                        false),
                Arguments.of(
                        Named.of("composition stale differs → 409 before write", "c-stale-diff"),
                        WriterMode.COMPOSITION,
                        4L,
                        true,
                        true,
                        false),
                Arguments.of(
                        Named.of("golden match equal → no-op", "g-eq"),
                        WriterMode.GOLDEN,
                        5L,
                        false,
                        false,
                        true),
                Arguments.of(
                        Named.of("golden match differs → transition", "g-diff"),
                        WriterMode.GOLDEN,
                        5L,
                        true,
                        false,
                        false),
                Arguments.of(
                        Named.of("golden stale equal → 409; equality does not forgive", "g-stale-eq"),
                        WriterMode.GOLDEN,
                        4L,
                        false,
                        true,
                        false),
                Arguments.of(
                        Named.of("golden stale differs → 409", "g-stale-diff"),
                        WriterMode.GOLDEN,
                        4L,
                        true,
                        true,
                        false));
    }

    @Test
    void resetLossHarnessInvokesOnceWithoutRetry() {
        AtomicLong capturedExpected = new AtomicLong(-1L);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger versionLookups = new AtomicInteger();

        Portfolio portfolio = presentPortfolio(3L);
        when(portfolioRepository.findByUserId("u1"))
                .thenAnswer(
                        inv -> {
                            versionLookups.incrementAndGet();
                            return List.of(portfolio);
                        });

        TestOnlyResetHarness harness =
                new TestOnlyResetHarness(
                        service,
                        goldenPreparer,
                        () -> {
                            long expected = 3L;
                            capturedExpected.compareAndSet(-1L, expected);
                            invocations.incrementAndGet();
                            // Simulate arriving stale / losing CAS eligibility without a second lookup.
                            return service.replace(
                                    "u1",
                                    capturedExpected.get() - 1L,
                                    List.of(),
                                    goldenPreparer);
                        });

        assertThatThrownBy(harness::invokeOnce)
                .isInstanceOf(PortfolioVersionConflictException.class);

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(versionLookups.get()).isEqualTo(1);
        assertThat(capturedExpected.get()).isEqualTo(3L);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void absentAggregateWithNonZeroExpectedVersionConflictsBeforeValidation() {
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        7L,
                                        List.of(new RawIntent("AAPL", new BigDecimal("-1"))),
                                        preparer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(0L));

        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
        verify(portfolioRepository, never()).saveAndFlush(any());
    }

    @Test
    void versionMismatchOutranksSemanticAndCatalogFailures() {
        Portfolio portfolio = presentPortfolio(3L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        2L,
                                        List.of(new RawIntent("AAPL", new BigDecimal("-1"))),
                                        preparer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(3L));

        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
    }

    @Test
    void quantityOutOfDomainAggregatesBeforeDuplicateCheck() {
        Portfolio portfolio = presentPortfolio(0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("AAPL", new BigDecimal("0")),
                                                new RawIntent("AAPL", new BigDecimal("-2")),
                                                new RawIntent("MSFT", BigDecimal.ONE)),
                                        preparer))
                .isInstanceOf(QuantityOutOfDomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((QuantityOutOfDomainException) ex).tickers())
                                        .containsExactly("AAPL"));

        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void multipleDistinctQuantityOffendersAggregateInRequestOrder() {
        Portfolio portfolio = presentPortfolio(0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("MSFT", new BigDecimal("0")),
                                                new RawIntent("AAPL", new BigDecimal("-1")),
                                                new RawIntent("MSFT", new BigDecimal("-3")),
                                                new RawIntent("GOOG", BigDecimal.ONE)),
                                        preparer))
                .isInstanceOf(QuantityOutOfDomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((QuantityOutOfDomainException) ex).tickers())
                                        .containsExactly("MSFT", "AAPL"));

        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void nullQuantityRejectedByQuantityDomainAfterVersionMatch() {
        Portfolio portfolio = presentPortfolio(0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(new RawIntent("AAPL", null)),
                                        preparer))
                .isInstanceOf(QuantityOutOfDomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((QuantityOutOfDomainException) ex).tickers())
                                        .containsExactly("AAPL"));

        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void duplicateTickersRejectedAfterQuantityPasses() {
        Portfolio portfolio = presentPortfolio(0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("AAPL", BigDecimal.ONE),
                                                new RawIntent("MSFT", BigDecimal.TEN),
                                                new RawIntent("AAPL", BigDecimal.TWO)),
                                        preparer))
                .isInstanceOf(DuplicateTickerException.class)
                .satisfies(
                        ex ->
                                assertThat(((DuplicateTickerException) ex).tickers())
                                        .containsExactly("AAPL"));
    }

    @Test
    void multipleDuplicatedTickersAggregateInFirstOffendingRequestOrder() {
        Portfolio portfolio = presentPortfolio(0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("AAPL", BigDecimal.ONE),
                                                new RawIntent("MSFT", BigDecimal.TEN),
                                                new RawIntent("GOOG", BigDecimal.ONE),
                                                new RawIntent("AAPL", BigDecimal.TWO),
                                                new RawIntent("MSFT", BigDecimal.ONE)),
                                        preparer))
                .isInstanceOf(DuplicateTickerException.class)
                .satisfies(
                        ex ->
                                assertThat(((DuplicateTickerException) ex).tickers())
                                        .containsExactly("AAPL", "MSFT"));
    }

    @Test
    void noOpSkipsParentCasWhenDesiredEqualsLocked() {
        Portfolio portfolio = presentPortfolio(5L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));
        when(preparer.materialise(eq(List.of()), anyList())).thenReturn(List.of());

        CompositionResult result = service.replace("u1", 5L, List.of(), preparer);

        assertThat(result.noOp()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.version()).isEqualTo(5L);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
    }

    @Test
    void replaceSignatureDoesNotAcceptCatalogVersion() throws Exception {
        var method =
                HoldingReplacementService.class.getMethod(
                        "replace", String.class, long.class, List.class, TuplePreparer.class);
        assertThat(method.getParameterTypes())
                .containsExactly(String.class, long.class, List.class, TuplePreparer.class);
    }

    private static TuplePreparer counting(TuplePreparer delegate, AtomicInteger materialiseCalls) {
        return (intent, locked) -> {
            materialiseCalls.incrementAndGet();
            return delegate.materialise(intent, locked);
        };
    }

    private Portfolio presentPortfolio(long version) {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", version);
        ReflectionTestUtils.setField(portfolio, "createdAt", PORTFOLIO_CREATED);
        ReflectionTestUtils.setField(portfolio, "updatedAt", PORTFOLIO_UPDATED);
        return portfolio;
    }

    private static com.wealth.portfolio.AssetHolding holdingFrom(
            Portfolio portfolio,
            String ticker,
            BigDecimal quantity,
            BigDecimal avgCostBasis,
            String currency,
            String source,
            Instant asOf) {
        com.wealth.portfolio.AssetHolding h =
                new com.wealth.portfolio.AssetHolding(portfolio, ticker, quantity);
        h.setAvgCostBasis(avgCostBasis);
        h.setCostBasisCurrency(currency);
        h.setCostBasisSource(source);
        h.setCostBasisAsOf(asOf);
        return h;
    }

    private static BigDecimal quantityFor(String ticker) {
        return BigDecimal.valueOf(Math.floorMod(ticker.hashCode(), 50) + 1);
    }

    enum WriterMode {
        COMPOSITION,
        GOLDEN
    }

    /**
     * Test-only reset invocation harness (Wave 6 production rewrite is out of scope). Captures
     * expectedVersion once at eligibility and performs a single {@code replace} with {@link
     * GoldenStateTuplePreparer}.
     */
    static final class TestOnlyResetHarness {
        private final HoldingReplacementService service;
        private final GoldenStateTuplePreparer preparer;
        private final ResetAction action;

        TestOnlyResetHarness(
                HoldingReplacementService service,
                GoldenStateTuplePreparer preparer,
                ResetAction action) {
            this.service = service;
            this.preparer = preparer;
            this.action = action;
        }

        CompositionResult invokeOnce() {
            return action.run();
        }

        @FunctionalInterface
        interface ResetAction {
            CompositionResult run();
        }
    }
}
