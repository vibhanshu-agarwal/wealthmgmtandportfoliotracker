package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.seed.PortfolioSeedService;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoldenStateTuplePreparerTest {

    private static final Instant ANCHOR = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant OTHER_ANCHOR = Instant.parse("2021-06-15T12:00:00Z");
    private static final String USER = "user-golden";

    private SeedTickerRegistry registry;

    @BeforeEach
    void setUp() {
        SupportedCatalog catalog = SupportedCatalog.load();
        registry = new SeedTickerRegistry(catalog, catalog.seedView());
    }

    @Test
    void usesCallerSuppliedCostBasisAnchorNotAHardcodedMovingValue() {
        GoldenStateTuplePreparer preparer =
                new GoldenStateTuplePreparer(registry, USER, ANCHOR);

        List<DesiredHoldingState> desired = preparer.materialise(List.of(), List.of());

        assertThat(desired).isNotEmpty();
        assertThat(desired)
                .allSatisfy(row -> assertThat(row.costBasisAsOf()).isEqualTo(ANCHOR));
        assertThat(desired)
                .allSatisfy(row -> assertThat(row.costBasisSource()).isEqualTo("SEED"));
    }

    @Test
    void differentAnchorProducesDifferentAsOfWithoutChangingQuantityFormula() {
        List<DesiredHoldingState> a =
                new GoldenStateTuplePreparer(registry, USER, ANCHOR).materialise(List.of(), List.of());
        List<DesiredHoldingState> b =
                new GoldenStateTuplePreparer(registry, USER, OTHER_ANCHOR)
                        .materialise(List.of(), List.of());

        assertThat(a.getFirst().quantity()).isEqualByComparingTo(b.getFirst().quantity());
        assertThat(a.getFirst().avgCostBasis()).isEqualByComparingTo(b.getFirst().avgCostBasis());
        assertThat(a.getFirst().costBasisAsOf()).isNotEqualTo(b.getFirst().costBasisAsOf());
    }

    @Test
    void costBasisMatchesSeedServiceDeterministicFunction() {
        String ticker = registry.active().getFirst().ticker();
        BigDecimal qty = BigDecimal.valueOf(Math.floorMod(ticker.hashCode(), 50) + 1);
        GoldenStateTuplePreparer preparer =
                new GoldenStateTuplePreparer(registry, USER, ANCHOR);

        DesiredHoldingState row =
                preparer.materialise(List.of(new RawIntent(ticker, qty)), List.of()).getFirst();

        var seedTicker = registry.find(ticker).orElseThrow();
        BigDecimal seedPrice =
                com.wealth.portfolio.seed.DeterministicPriceCalculator.compute(
                        seedTicker.basePrice(), ticker, USER);
        BigDecimal expectedBasis =
                PortfolioSeedService.computeDeterministicCostBasis(seedPrice, ticker, USER);

        assertThat(row.avgCostBasis()).isEqualByComparingTo(expectedBasis);
        assertThat(row.costBasisCurrency()).isEqualTo(seedTicker.quoteCurrency());
    }
}
