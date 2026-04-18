package com.wealth.portfolio;

import java.math.BigDecimal;

/**
 * Domain port for foreign exchange rate lookup.
 *
 * <p>Implementations must be thread-safe. Rates are directional:
 * {@code getRate("EUR", "USD")} returns how many USD one EUR buys.
 *
 * <p>If {@code fromCurrency.equals(toCurrency)}, implementations MUST return
 * {@link BigDecimal#ONE} without performing any lookup or network call.
 */
public interface FxRateProvider {

    /**
     * Returns the exchange rate to convert 1 unit of {@code fromCurrency}
     * into {@code toCurrency}.
     *
     * @param fromCurrency ISO 4217 currency code (e.g. "EUR")
     * @param toCurrency   ISO 4217 currency code (e.g. "USD")
     * @return rate &gt; 0; exactly {@link BigDecimal#ONE} when fromCurrency.equals(toCurrency)
     * @throws FxRateUnavailableException if the rate cannot be resolved
     */
    BigDecimal getRate(String fromCurrency, String toCurrency);
}
