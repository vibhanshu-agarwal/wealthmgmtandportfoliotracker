package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link PortfolioService#getSummary} with a mocked {@link FxRateProvider}. No
 * Spring context, no database, no network — pure arithmetic verification.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceFxTest {

  private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

  @Mock FxRateProvider fxRateProvider;
  @Mock PortfolioRepository portfolioRepository;
  @Mock JdbcTemplate jdbcTemplate;
  @Mock UserRepository userRepository;

  PortfolioService service;

  @BeforeEach
  void setUp() {
    FxProperties props = new FxProperties("USD", null, null);
    service =
        new PortfolioService(
            portfolioRepository, jdbcTemplate, userRepository, fxRateProvider, props);
  }

  // Property 7: FX-normalised total value — 10 × 100 EUR × 1.08 = 1080.0000 USD
  @Test
  @SuppressWarnings("unchecked")
  void totalValueConvertsEurHoldingToUsd() {
    when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
    when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
        .thenReturn(
            List.of(
                new HoldingValuationRow(
                    "EURASSET", new BigDecimal("10"), new BigDecimal("100"), "EUR")));
    when(fxRateProvider.getRate("EUR", "USD")).thenReturn(new BigDecimal("1.08"));

    PortfolioSummaryDto summary = service.getSummary(USER_ID);

    assertThat(summary.totalValue()).isEqualByComparingTo("1080.0000");
    verify(fxRateProvider).getRate("EUR", "USD");
  }

  // Property 8: same-currency short-circuit — no FxRateProvider call for USD holdings
  @Test
  @SuppressWarnings("unchecked")
  void sameCurrencyHoldingsDoNotCallFxProvider() {
    when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
    when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
        .thenReturn(
            List.of(
                new HoldingValuationRow(
                    "AAPL", new BigDecimal("5"), new BigDecimal("200"), "USD")));

    PortfolioSummaryDto summary = service.getSummary(USER_ID);

    assertThat(summary.totalValue()).isEqualByComparingTo("1000.0000");
    verifyNoInteractions(fxRateProvider);
  }

  // Property 9: baseCurrency propagation — DTO carries the configured base currency
  @Test
  @SuppressWarnings("unchecked")
  void summaryDtoCarriesBaseCurrency() {
    when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
    when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
        .thenReturn(List.of());

    PortfolioSummaryDto summary = service.getSummary(USER_ID);

    assertThat(summary.baseCurrency()).isEqualTo("USD");
  }

  // FxRateUnavailableException propagates to caller (fail-fast for unrecognised currencies)
  @Test
  @SuppressWarnings("unchecked")
  void unavailableRatePropagatesAsFxRateUnavailableException() {
    when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
    lenient().when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
        .thenReturn(
            List.of(
                new HoldingValuationRow(
                    "EXOTIC", new BigDecimal("1"), new BigDecimal("50"), "XYZ")));
    when(fxRateProvider.getRate("XYZ", "USD"))
        .thenThrow(new FxRateUnavailableException("XYZ", "USD", null));

    assertThatThrownBy(() -> service.getSummary(USER_ID))
        .isInstanceOf(FxRateUnavailableException.class)
        .hasMessageContaining("XYZ");
  }

  // Multi-currency portfolio: USD + EUR holdings aggregated correctly
  @Test
  @SuppressWarnings("unchecked")
  void multiCurrencyPortfolioAggregatesCorrectly() {
    when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
    when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), (Object[]) any()))
        .thenReturn(
            List.of(
                new HoldingValuationRow(
                    "AAPL",
                    new BigDecimal("5"),
                    new BigDecimal("200"),
                    "USD"), // 5 × 200 × 1.0 = 1000
                new HoldingValuationRow(
                    "EURASSET",
                    new BigDecimal("10"),
                    new BigDecimal("100"),
                    "EUR") // 10 × 100 × 1.08 = 1080
                ));
    when(fxRateProvider.getRate("EUR", "USD")).thenReturn(new BigDecimal("1.08"));

    PortfolioSummaryDto summary = service.getSummary(USER_ID);

    // 1000 + 1080 = 2080
    assertThat(summary.totalValue()).isEqualByComparingTo("2080.0000");
    verifyNoMoreInteractions(fxRateProvider); // only called once for EUR
  }
}
