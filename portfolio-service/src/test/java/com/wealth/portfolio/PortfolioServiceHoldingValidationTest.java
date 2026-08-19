package com.wealth.portfolio;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.portfolio.catalog.SupportedAssetValidator;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceHoldingValidationTest {

    private static final String USER_ID = "user-001";

    @Mock PortfolioRepository portfolioRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserRepository userRepository;
    @Mock FxRateProvider fxRateProvider;

    private PortfolioService service;
    private UUID portfolioId;
    private Portfolio portfolio;

    @BeforeEach
    void setUp() {
        SupportedCatalog catalog = SupportedCatalog.load();
        SupportedAssetValidator validator = new SupportedAssetValidator(catalog, true);
        service =
                new PortfolioService(
                        portfolioRepository,
                        jdbcTemplate,
                        userRepository,
                        fxRateProvider,
                        new FxProperties("USD", null, null, null),
                        validator,
                        com.wealth.portfolio.freshness.AssetPriceFreshnessProperties.defaults());
        portfolioId = UUID.randomUUID();
        portfolio = new Portfolio(USER_ID);
        ReflectionTestUtils.setField(portfolio, "id", portfolioId);
    }

    @Test
    void addHolding_rejectsUnknownTickerWithoutPersisting() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));

        assertThatThrownBy(
                        () -> service.addHolding(USER_ID, portfolioId, "NOT_A_TICKER", BigDecimal.ONE))
                .isInstanceOf(UnsupportedAssetException.class);

        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void addHolding_rejectsIncreaseOfDeprecatedPositionWithoutPersisting() {
        AssetHolding existing = new AssetHolding(portfolio, "TATAMOTORS.NS", new BigDecimal("10"));
        portfolio.addHolding(existing);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.addHolding(
                                        USER_ID, portfolioId, "TATAMOTORS.NS", new BigDecimal("15")))
                .isInstanceOf(UnsupportedAssetException.class);

        verify(portfolioRepository, never()).save(any());
    }
}
