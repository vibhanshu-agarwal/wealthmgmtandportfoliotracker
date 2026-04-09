package com.wealth.portfolio.fx;

import com.wealth.portfolio.FxRateUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StaticFxRateProviderTest {

    private StaticFxRateProvider provider;

    @BeforeEach
    void setUp() {
        var rates = Map.of(
                "USD", BigDecimal.ONE,
                "EUR", new BigDecimal("0.92"),
                "GBP", new BigDecimal("0.79"),
                "JPY", new BigDecimal("149.50")
        );
        var props = new FxProperties(
                "USD",
                new FxProperties.LocalProperties(rates, 60_000L),
                null
        );
        provider = new StaticFxRateProvider(props);
    }

    // Property 1: same-currency identity
    @Test
    void sameCurrencyReturnsOne() {
        assertThat(provider.getRate("USD", "USD")).isEqualByComparingTo("1");
        assertThat(provider.getRate("EUR", "EUR")).isEqualByComparingTo("1");
    }

    // Property 4: cross-rate formula — EUR→USD = 1 / 0.92 ≈ 1.0870
    @Test
    void eurToUsdUsesCorrectCrossRateFormula() {
        BigDecimal rate = provider.getRate("EUR", "USD");
        // ratesFromUsd[USD] / ratesFromUsd[EUR] = 1.0 / 0.92
        assertThat(rate).isCloseTo(new BigDecimal("1.0870"), within(new BigDecimal("0.0001")));
    }

    // Property 3: inverse round-trip — getRate(A,B) × getRate(B,A) ≈ 1
    @Test
    void inverseRoundTripHoldsForEurUsd() {
        BigDecimal eurToUsd = provider.getRate("EUR", "USD");
        BigDecimal usdToEur = provider.getRate("USD", "EUR");
        assertThat(eurToUsd.multiply(usdToEur))
                .isCloseTo(BigDecimal.ONE, within(new BigDecimal("0.0001")));
    }

    @Test
    void inverseRoundTripHoldsForGbpJpy() {
        BigDecimal gbpToJpy = provider.getRate("GBP", "JPY");
        BigDecimal jpyToGbp = provider.getRate("JPY", "GBP");
        assertThat(gbpToJpy.multiply(jpyToGbp))
                .isCloseTo(BigDecimal.ONE, within(new BigDecimal("0.0001")));
    }

    // Unknown currency throws FxRateUnavailableException
    @Test
    void unknownFromCurrencyThrowsFxRateUnavailableException() {
        assertThatThrownBy(() -> provider.getRate("XYZ", "USD"))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("XYZ");
    }

    @Test
    void unknownToCurrencyThrowsFxRateUnavailableException() {
        assertThatThrownBy(() -> provider.getRate("USD", "XYZ"))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("XYZ");
    }
}
