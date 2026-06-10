package com.wealth.portfolio;

/**
 * Maps internal seed/catalogue asset-class values to canonical UI display classes.
 *
 * <p>Internal values come from {@code seed-tickers.json} and the {@code SeedTickerRegistry}:
 * {@code US_EQUITY}, {@code NSE}, {@code CRYPTO}, {@code FOREX}.
 *
 * <p>Display values are consumed by the frontend for allocation and per-holding labelling.
 * Any unrecognised internal value maps to {@code OTHER} — never silently defaulted to STOCK.
 *
 * <h2>Mapping table</h2>
 * <pre>
 * US_EQUITY  → STOCK
 * NSE        → STOCK        (Indian equities are equities)
 * CRYPTO     → CRYPTO
 * FOREX      → CASH         (FX pairs are treated as cash-equivalent)
 * null / *   → OTHER
 * </pre>
 *
 * <p>Extend this mapping as new asset classes are introduced in {@code seed-tickers.json}.
 */
public final class DisplayAssetClassMapper {

    private DisplayAssetClassMapper() {}

    /** Display values exposed on the analytics/holdings contract. */
    public static final String STOCK     = "STOCK";
    public static final String CRYPTO    = "CRYPTO";
    public static final String BOND      = "BOND";
    public static final String CASH      = "CASH";
    public static final String COMMODITY = "COMMODITY";
    public static final String OTHER     = "OTHER";

    /**
     * Maps a raw {@code assetClass} value from the seed registry to a canonical display value.
     *
     * @param assetClass raw asset-class string (e.g. "US_EQUITY"); may be null
     * @return one of {@link #STOCK}, {@link #CRYPTO}, {@link #BOND}, {@link #CASH},
     *         {@link #COMMODITY}, or {@link #OTHER} — never null
     */
    public static String map(String assetClass) {
        if (assetClass == null) {
            return OTHER;
        }
        return switch (assetClass) {
            case "US_EQUITY" -> STOCK;
            case "NSE"       -> STOCK;
            case "CRYPTO"    -> CRYPTO;
            case "FOREX"     -> CASH;
            case "BOND"      -> BOND;
            case "COMMODITY" -> COMMODITY;
            default          -> OTHER;
        };
    }
}
