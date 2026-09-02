package com.wealth.portfolio.seed;

import com.wealth.portfolio.composition.CompositionResult;
import com.wealth.portfolio.composition.DesiredHoldingState;
import com.wealth.portfolio.composition.GoldenStateTuplePreparer;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.composition.RawIntent;
import com.wealth.portfolio.composition.TuplePreparer;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the golden-state seeder.
 *
 * <p>This class previously asserted the shape of the {@code market_price_history} batch the
 * seeder wrote. That behaviour is gone: the seeder writes portfolios and holdings only, and
 * has no JDBC collaborator at all, so a market-data write is now a compile-time impossibility
 * here rather than something a test has to police. The regression guard that the global price
 * tables are left untouched lives in {@code PortfolioSeedServiceIT}, against a real database.
 *
 * <p>It also previously asserted the delete-then-recreate path — {@code deleteAll}, a fresh
 * {@code save(new Portfolio(..))}, and a direct {@code saveAll} of children. That path is gone
 * too, and with it the identity churn it caused. What is asserted here now is that the seed
 * delegates exactly once to the shared replacement transaction, hands it the caller's observed
 * version verbatim, and supplies a golden preparer whose tuple is still deterministic.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioSeedServiceTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final Instant COST_BASIS_ANCHOR = Instant.parse("2019-06-15T12:30:00Z");
    private static final DemoProperties DEMO_PROPERTIES = new DemoProperties(false, COST_BASIS_ANCHOR);
    private static final SeedTicker AAPL = new SeedTicker(
            "AAPL", "US_EQUITY", "USD", new BigDecimal("190.00"), "Apple Inc.", null);

    @Mock private HoldingReplacementService replacementService;
    @Mock private SeedTickerRegistry registry;

    private PortfolioSeedService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioSeedService(replacementService, registry, DEMO_PROPERTIES);
        lenient().when(registry.active()).thenReturn(List.of(AAPL));
    }

    private static CompositionResult result(UUID portfolioId, long version, int holdings) {
        return new CompositionResult(
                portfolioId,
                E2E_USER,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                version,
                java.util.Collections.nCopies(
                        holdings,
                        new DesiredHoldingState(
                                "AAPL", BigDecimal.ONE, BigDecimal.TEN, "USD", "SEED",
                                COST_BASIS_ANCHOR)),
                false,
                false);
    }

    // ---------------------------------------------------------------- delegation

    @Test
    void seedDelegatesOnceToTheReplacementTransactionWithEmptyIntent() {
        UUID portfolioId = UUID.randomUUID();
        when(replacementService.replace(anyString(), anyLong(), any(), any()))
                .thenReturn(result(portfolioId, 5L, 1));

        var seedResult = service.seed(E2E_USER, 4L);

        ArgumentCaptor<TuplePreparer> preparerCaptor = ArgumentCaptor.forClass(TuplePreparer.class);
        verify(replacementService, times(1))
                .replace(eq(E2E_USER), eq(4L), eq(List.of()), preparerCaptor.capture());
        verifyNoMoreInteractions(replacementService);

        assertThat(preparerCaptor.getValue())
                .as("full-golden mode is empty intent plus the golden preparer")
                .isInstanceOf(GoldenStateTuplePreparer.class);

        assertThat(seedResult.portfolioId()).isEqualTo(portfolioId);
        assertThat(seedResult.holdingsInserted())
                .as("holdingsInserted is the resulting active-set cardinality")
                .isEqualTo(1);
    }

    /**
     * A genuine integer zero must reach the transaction as zero — the Absent_Aggregate
     * precondition — rather than being treated as "no version supplied".
     */
    @ParameterizedTest(name = "expectedVersion {0} is forwarded verbatim")
    @ValueSource(longs = {0L, 1L, 7L, Long.MAX_VALUE})
    void observedVersionIsForwardedVerbatim(long expectedVersion) {
        when(replacementService.replace(anyString(), anyLong(), any(), any()))
                .thenReturn(result(UUID.randomUUID(), expectedVersion + 1, 1));

        service.seed(E2E_USER, expectedVersion);

        verify(replacementService).replace(eq(E2E_USER), eq(expectedVersion), eq(List.of()), any());
    }

    @Test
    void seedNeverReadsAVersionOfItsOwn() {
        when(replacementService.replace(anyString(), anyLong(), any(), any()))
                .thenReturn(result(UUID.randomUUID(), 1L, 1));

        service.seed(E2E_USER, 0L);

        // The only collaborator that may be consulted before the boundary is the catalog
        // registry, via the preparer. Nothing may read stored portfolio state to invent or
        // correct a version.
        verifyNoMoreInteractions(replacementService);
    }

    /**
     * A versionless overload would let a caller skip the precondition entirely, so the type
     * itself must not offer one.
     */
    @Test
    void noVersionlessSeedOverloadSurvives() {
        List<Method> seedMethods = Arrays.stream(PortfolioSeedService.class.getMethods())
                .filter(m -> "seed".equals(m.getName()))
                .toList();

        assertThat(seedMethods)
                .as("exactly one seed method must exist, and it must require an observed version")
                .singleElement()
                .satisfies(m -> assertThat(m.getParameterTypes())
                        .containsExactly(String.class, long.class));
    }

    // ---------------------------------------------------------------- conflict handling

    @Test
    void versionConflictPropagatesWithoutRetryOrRecovery() {
        when(replacementService.replace(anyString(), anyLong(), any(), any()))
                .thenThrow(new PortfolioVersionConflictException(9L));

        assertThatThrownBy(() -> service.seed(E2E_USER, 4L))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(e -> assertThat(
                        ((PortfolioVersionConflictException) e).currentVersion().getAsLong())
                        .isEqualTo(9L));

        verify(replacementService, times(1)).replace(anyString(), anyLong(), any(), any());
        verifyNoMoreInteractions(replacementService);
    }

    // ---------------------------------------------------------------- deterministic tuple

    @Test
    void suppliedPreparerStillProducesTheDeterministicGoldenTuple() {
        when(replacementService.replace(anyString(), anyLong(), any(), any()))
                .thenReturn(result(UUID.randomUUID(), 1L, 1));

        service.seed(E2E_USER, 0L);

        ArgumentCaptor<TuplePreparer> preparerCaptor = ArgumentCaptor.forClass(TuplePreparer.class);
        verify(replacementService).replace(anyString(), anyLong(), any(), preparerCaptor.capture());

        List<DesiredHoldingState> tuple =
                preparerCaptor.getValue().materialise(List.<RawIntent>of(), List.of());

        assertThat(tuple).hasSize(1);
        DesiredHoldingState aapl = tuple.get(0);
        assertThat(aapl.ticker()).isEqualTo("AAPL");
        assertThat(aapl.costBasisSource()).isEqualTo("SEED");
        assertThat(aapl.costBasisCurrency()).isEqualTo("USD");
        assertThat(aapl.costBasisAsOf())
                .as("the configured anchor, never a moving Instant.now()-derived value")
                .isEqualTo(COST_BASIS_ANCHOR);

        // Cost basis is seedPrice ± bounded jitter, both derived from basePrice alone.
        BigDecimal seedPrice =
                DeterministicPriceCalculator.compute(AAPL.basePrice(), AAPL.ticker(), E2E_USER);
        BigDecimal expected =
                PortfolioSeedService.computeDeterministicCostBasis(seedPrice, AAPL.ticker(), E2E_USER);
        assertThat(aapl.avgCostBasis()).isEqualByComparingTo(expected);
    }

    @Test
    void desiredHoldingsRemainsPureAndDeterministic() {
        List<PortfolioSeedService.DesiredHolding> first = service.desiredHoldings(E2E_USER);
        List<PortfolioSeedService.DesiredHolding> second = service.desiredHoldings(E2E_USER);

        assertThat(first).hasSize(1);
        assertThat(second).isEqualTo(first);
        assertThat(first.get(0).costBasisAsOf()).isEqualTo(COST_BASIS_ANCHOR);

        // The pure API used by the initializer and diagnostics must not touch the writer.
        verifyNoMoreInteractions(replacementService);
    }
}
