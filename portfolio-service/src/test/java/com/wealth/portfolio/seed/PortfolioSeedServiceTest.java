package com.wealth.portfolio.seed;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * <p>What remains worth asserting at unit level is that cost basis stays deterministic without
 * any price write — it is derived in-memory from the catalogue's {@code basePrice}.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioSeedServiceTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final SeedTicker AAPL = new SeedTicker(
            "AAPL", "US_EQUITY", "USD", new BigDecimal("190.00"), "Apple Inc.", null);

    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private SeedTickerRegistry registry;

    private PortfolioSeedService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioSeedService(portfolioRepository, assetHoldingRepository, registry);
        when(registry.all()).thenReturn(List.of(AAPL));
        when(portfolioRepository.findByUserId(E2E_USER)).thenReturn(List.of());

        when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(inv -> {
            Portfolio p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });
        when(assetHoldingRepository.saveAll(any())).thenReturn(List.of());
    }

    @Test
    void seed_derivesDeterministicCostBasisFromBasePriceWithoutAPriceWrite() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssetHolding>> holdingsCaptor = ArgumentCaptor.forClass(List.class);

        var result = service.seed(E2E_USER);

        assertThat(result.holdingsInserted()).isEqualTo(1);

        org.mockito.Mockito.verify(assetHoldingRepository).saveAll(holdingsCaptor.capture());
        List<AssetHolding> saved = holdingsCaptor.getValue();
        assertThat(saved).hasSize(1);

        AssetHolding aapl = saved.get(0);
        assertThat(aapl.getAssetTicker()).isEqualTo("AAPL");
        assertThat(aapl.getCostBasisSource()).isEqualTo("SEED");
        assertThat(aapl.getCostBasisCurrency()).isEqualTo("USD");
        assertThat(aapl.getCostBasisAsOf()).isNotNull();

        // Cost basis is seedPrice ± bounded jitter, both derived from basePrice alone.
        BigDecimal seedPrice =
                DeterministicPriceCalculator.compute(AAPL.basePrice(), AAPL.ticker(), E2E_USER);
        BigDecimal expected =
                PortfolioSeedService.computeDeterministicCostBasis(seedPrice, AAPL.ticker(), E2E_USER);
        assertThat(aapl.getAvgCostBasis()).isEqualByComparingTo(expected);
    }

    @Test
    void seed_isDeterministicAcrossInvocations() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssetHolding>> captor = ArgumentCaptor.forClass(List.class);

        service.seed(E2E_USER);
        service.seed(E2E_USER);

        org.mockito.Mockito.verify(assetHoldingRepository, org.mockito.Mockito.times(2))
                .saveAll(captor.capture());
        List<List<AssetHolding>> runs = captor.getAllValues();

        assertThat(runs.get(1).get(0).getAvgCostBasis())
                .as("cost basis must be identical across seed runs for the same (ticker, userId)")
                .isEqualByComparingTo(runs.get(0).get(0).getAvgCostBasis());
        assertThat(runs.get(1).get(0).getQuantity())
                .isEqualByComparingTo(runs.get(0).get(0).getQuantity());
    }
}
