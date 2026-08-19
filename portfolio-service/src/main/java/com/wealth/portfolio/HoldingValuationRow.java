package com.wealth.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal query projection carrying the data needed to compute a single holding's
 * FX-converted value and asset-price freshness. Not a public DTO.
 *
 * @param assetTicker       the ticker symbol of the asset
 * @param quantity          number of units held
 * @param currentPrice      latest market price in {@code quoteCurrency}; null when no price row
 * @param quoteCurrency     ISO 4217 code; null when no price row
 * @param priceRowPresent   whether {@code market_prices} has a row for this ticker
 * @param observedAt        observation timestamp; null means {@code UNKNOWN} when a row exists
 */
record HoldingValuationRow(
        String assetTicker,
        BigDecimal quantity,
        BigDecimal currentPrice,
        String quoteCurrency,
        boolean priceRowPresent,
        Instant observedAt
) {
    HoldingValuationRow(
            String assetTicker, BigDecimal quantity, BigDecimal currentPrice, String quoteCurrency) {
        this(assetTicker, quantity, currentPrice, quoteCurrency, true, Instant.parse("2026-08-19T08:00:00Z"));
    }
}
