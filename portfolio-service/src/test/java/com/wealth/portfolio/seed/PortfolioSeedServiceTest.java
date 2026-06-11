package com.wealth.portfolio.seed;

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for history-batch seeding (24h Delta Hybrid Fix — Layer A2).
 */
@ExtendWith(MockitoExtension.class)
class PortfolioSeedServiceTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final SeedTicker AAPL = new SeedTicker(
            "AAPL", "US_EQUITY", "USD", new BigDecimal("190.00"), "Apple Inc.", null);

    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private SeedTickerRegistry registry;
    @Mock private NamedParameterJdbcTemplate jdbc;

    private PortfolioSeedService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioSeedService(portfolioRepository, assetHoldingRepository, registry, jdbc);
        when(registry.all()).thenReturn(List.of(AAPL));
        when(portfolioRepository.findByUserId(E2E_USER)).thenReturn(List.of());

        when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(inv -> {
            Portfolio p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });
        when(assetHoldingRepository.saveAll(any())).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1});
    }

    @Test
    void seed_historyBatchUsesComputeHistoryNotCurrentPrice() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SqlParameterSource[]> batchCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);

        service.seed(E2E_USER);

        verify(jdbc, times(2)).batchUpdate(sqlCaptor.capture(), batchCaptor.capture());

        assertThat(sqlCaptor.getAllValues().get(1)).contains("market_price_history");

        SqlParameterSource historyRow = batchCaptor.getAllValues().get(1)[0];
        BigDecimal historyPrice = (BigDecimal) historyRow.getValue("price");

        BigDecimal currentPrice = DeterministicPriceCalculator.compute(AAPL.basePrice(), AAPL.ticker(), E2E_USER);
        BigDecimal expectedHistory = DeterministicPriceCalculator.computeHistory(currentPrice, AAPL.ticker(), E2E_USER);

        assertThat(historyPrice).isEqualByComparingTo(expectedHistory);
        assertThat(historyPrice).isNotEqualByComparingTo(currentPrice);
    }
}
