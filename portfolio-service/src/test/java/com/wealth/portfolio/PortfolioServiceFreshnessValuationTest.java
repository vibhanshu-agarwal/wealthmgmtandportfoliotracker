package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import com.wealth.portfolio.freshness.AssetPriceFreshnessProperties;
import com.wealth.portfolio.freshness.FreshnessState;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8.6: valuation rules for stale / unknown / missing, empty portfolio, and unchanged FX skip.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceFreshnessValuationTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final Instant NOWISH = Instant.parse("2026-08-19T08:00:00Z");

    @Mock FxRateProvider fxRateProvider;
    @Mock PortfolioRepository portfolioRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserRepository userRepository;

    PortfolioService service;

    @BeforeEach
    void setUp() {
        service =
                new PortfolioService(
                        portfolioRepository,
                        jdbcTemplate,
                        userRepository,
                        fxRateProvider,
                        new FxProperties("USD", null, null, null),
                        mock(),
                        AssetPriceFreshnessProperties.defaults());
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    }

    @Test
    void emptyPortfolio_isFreshWithZeroCountsAndAbsentTimestamp() {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        PortfolioSummaryDto summary = service.getSummary(USER_ID);

        assertThat(summary.totalValue()).isEqualByComparingTo("0");
        assertThat(summary.partialValuation()).isFalse();
        assertThat(summary.assetPriceFreshness().state()).isEqualTo(FreshnessState.FRESH);
        assertThat(summary.assetPriceFreshness().staleHoldings()).isZero();
        assertThat(summary.assetPriceFreshness().unknownPriceHoldings()).isZero();
        assertThat(summary.assetPriceFreshness().missingPriceHoldings()).isZero();
        assertThat(summary.assetPriceFreshness().oldestKnownAssetPriceObservationTimestamp()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleHolding_isIncludedInTotal_andDoesNotSetPartialValuation() {
        Instant staleAt = NOWISH.minusSeconds(51 * 3600);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new HoldingValuationRow(
                                        "AAPL",
                                        new BigDecimal("5"),
                                        new BigDecimal("200"),
                                        "USD",
                                        true,
                                        staleAt)));

        PortfolioSummaryDto summary = service.getSummary(USER_ID);

        assertThat(summary.totalValue()).isEqualByComparingTo("1000.0000");
        assertThat(summary.partialValuation()).isFalse();
        assertThat(summary.assetPriceFreshness().state()).isEqualTo(FreshnessState.STALE);
        assertThat(summary.assetPriceFreshness().staleHoldings()).isEqualTo(1);
        assertThat(summary.assetPriceFreshness().oldestKnownAssetPriceObservationTimestamp())
                .isEqualTo(staleAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownHolding_isIncludedInTotal() {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new HoldingValuationRow(
                                        "AAPL",
                                        new BigDecimal("5"),
                                        new BigDecimal("200"),
                                        "USD",
                                        true,
                                        null)));

        PortfolioSummaryDto summary = service.getSummary(USER_ID);

        assertThat(summary.totalValue()).isEqualByComparingTo("1000.0000");
        assertThat(summary.partialValuation()).isFalse();
        assertThat(summary.assetPriceFreshness().state()).isEqualTo(FreshnessState.UNKNOWN);
        assertThat(summary.assetPriceFreshness().unknownPriceHoldings()).isEqualTo(1);
        assertThat(summary.assetPriceFreshness().oldestKnownAssetPriceObservationTimestamp()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingHolding_isExcludedAndSetsPartialValuation() {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new HoldingValuationRow("AAPL", new BigDecimal("5"), new BigDecimal("200"), "USD"),
                                new HoldingValuationRow(
                                        "MSFT", new BigDecimal("1"), null, null, false, null)));

        PortfolioSummaryDto summary = service.getSummary(USER_ID);

        assertThat(summary.totalValue()).isEqualByComparingTo("1000.0000");
        assertThat(summary.partialValuation()).isTrue();
        assertThat(summary.assetPriceFreshness().state()).isEqualTo(FreshnessState.MISSING);
        assertThat(summary.assetPriceFreshness().missingPriceHoldings()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableFx_stillExcludesAndSetsPartialValuation_withoutChangingFreshnessMeaning() {
        Instant observedAt = NOWISH.minusSeconds(3600);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(
                        List.of(
                                new HoldingValuationRow(
                                        "AAPL",
                                        new BigDecimal("5"),
                                        new BigDecimal("200"),
                                        "USD",
                                        true,
                                        observedAt),
                                new HoldingValuationRow(
                                        "EXOTIC",
                                        new BigDecimal("1"),
                                        new BigDecimal("50"),
                                        "XYZ",
                                        true,
                                        observedAt)));
        when(fxRateProvider.getRate("XYZ", "USD"))
                .thenThrow(new FxRateUnavailableException("XYZ", "USD", null));

        PortfolioSummaryDto summary = service.getSummary(USER_ID);

        assertThat(summary.totalValue()).isEqualByComparingTo("1000.0000");
        assertThat(summary.partialValuation()).isTrue();
        assertThat(summary.assetPriceFreshness().state()).isEqualTo(FreshnessState.FRESH);
        assertThat(summary.assetPriceFreshness().missingPriceHoldings()).isZero();
        assertThat(summary.assetPriceFreshness().oldestKnownAssetPriceObservationTimestamp())
                .isEqualTo(observedAt);
    }
}
