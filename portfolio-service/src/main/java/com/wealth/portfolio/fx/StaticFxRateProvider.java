package com.wealth.portfolio.fx;

import com.wealth.portfolio.FxRateProvider;
import com.wealth.portfolio.FxRateUnavailableException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-profile FX rate provider backed by a static in-memory map.
 *
 * <p>Rates are loaded from {@code fx.local.static-rates} in {@code application-local.yml}
 * and represent units of each currency per 1 USD (e.g. EUR: 0.92 means 1 USD = 0.92 EUR).
 *
 * <p>Cross-rates are derived on demand: {@code getRate(A, B) = ratesFromUsd[B] / ratesFromUsd[A]}.
 * No network calls are ever made by this adapter.
 */
@Service
@Profile("local")
public class StaticFxRateProvider implements FxRateProvider {

    private final Map<String, BigDecimal> ratesFromUsd;

    public StaticFxRateProvider(FxProperties props) {
        // Defensive copy into a ConcurrentHashMap for thread safety
        this.ratesFromUsd = new ConcurrentHashMap<>(
                props.local() != null && props.local().staticRates() != null
                        ? props.local().staticRates()
                        : Map.of("USD", BigDecimal.ONE)
        );
        // Ensure USD identity entry is always present
        this.ratesFromUsd.putIfAbsent("USD", BigDecimal.ONE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Same-currency pairs short-circuit to {@link BigDecimal#ONE}.
     * Cross-rates are computed as {@code ratesFromUsd[to] / ratesFromUsd[from]}.
     *
     * @throws FxRateUnavailableException if either currency is absent from the static map
     */
    @Override
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        BigDecimal rateFrom = ratesFromUsd.get(fromCurrency);
        BigDecimal rateTo   = ratesFromUsd.get(toCurrency);

        if (rateFrom == null || rateFrom.compareTo(BigDecimal.ZERO) == 0) {
            throw new FxRateUnavailableException(fromCurrency, toCurrency, null);
        }
        if (rateTo == null) {
            throw new FxRateUnavailableException(fromCurrency, toCurrency, null);
        }

        // rateTo / rateFrom  →  how many toCurrency units per 1 fromCurrency unit
        return rateTo.divide(rateFrom, MathContext.DECIMAL64);
    }
}
