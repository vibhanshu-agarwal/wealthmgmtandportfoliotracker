package com.wealth.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST DTO for a single asset's price, as served by {@code GET /api/market/prices}.
 *
 * <p>Wave 2 additions:
 * <ul>
 *   <li>{@code quoteCurrency} — the ISO currency code the price is quoted in.</li>
 *   <li>{@code previousReferencePrice} / {@code previousReferenceAt} — the prior reference
 *       observation, from which {@code changeAbsolute} / {@code changePercent} are derived.</li>
 *   <li>{@code changeAbsolute} / {@code changePercent} — nullable; null means "unavailable"
 *       (i.e. no reference observation exists yet). Never coerced to 0.</li>
 *   <li>{@code changeBasis} — label describing the reference used, e.g.
 *       {@code "WITHIN_24H_WINDOW"}, {@code "SINCE_PREVIOUS_SNAPSHOT"}, or null when
 *       change is unavailable.</li>
 * </ul>
 *
 * @param ticker                  asset symbol
 * @param currentPrice            latest price in {@code quoteCurrency}; null ⇒ unavailable
 * @param quoteCurrency           ISO currency code
 * @param observedAt              true observation time; never now() for missing data
 * @param previousReferencePrice  prior price used as the change baseline; nullable
 * @param previousReferenceAt     observation time of the prior price; nullable
 * @param changeAbsolute          currentPrice − previousReferencePrice; null ⇒ unavailable
 * @param changePercent           percentage change; null ⇒ unavailable
 * @param changeBasis             label for the reference window; null when change unavailable
 */
public record MarketPriceDto(
        String ticker,
        BigDecimal currentPrice,
        String quoteCurrency,
        Instant observedAt,
        BigDecimal previousReferencePrice,
        Instant previousReferenceAt,
        BigDecimal changeAbsolute,
        BigDecimal changePercent,
        String changeBasis
) {

    /** Convenience factory for the "price unavailable" case. */
    public static MarketPriceDto unavailable(String ticker) {
        return new MarketPriceDto(ticker, null, null, null, null, null, null, null, null);
    }

    /**
     * Derives nullable change fields from current vs reference price.
     * Returns null change when no reference is available.
     *
     * @param changeAbsoluteVal pre-computed absolute change, or null
     * @param changePercentVal  pre-computed percent change, or null
     * @param basisLabel        label for the reference window, or null
     */
    public static MarketPriceDto fromAssetPrice(AssetPrice price,
                                                BigDecimal changeAbsoluteVal,
                                                BigDecimal changePercentVal,
                                                String basisLabel) {
        return new MarketPriceDto(
                price.getTicker(),
                price.getCurrentPrice(),
                price.getQuoteCurrency(),
                price.getUpdatedAt(),
                price.getPreviousReferencePrice(),
                price.getPreviousReferenceAt(),
                changeAbsoluteVal,
                changePercentVal,
                basisLabel
        );
    }
}
