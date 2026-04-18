package com.wealth.portfolio;

import java.math.BigDecimal;

/**
 * Internal query projection carrying the data needed to compute a single holding's
 * FX-converted value. Not a public DTO — never serialised to HTTP responses.
 *
 * @param assetTicker   the ticker symbol of the asset
 * @param quantity      number of units held
 * @param currentPrice  latest market price in {@code quoteCurrency}
 * @param quoteCurrency ISO 4217 currency code in which {@code currentPrice} is denominated
 */
record HoldingValuationRow(
        String assetTicker,
        BigDecimal quantity,
        BigDecimal currentPrice,
        String quoteCurrency
) {
}
