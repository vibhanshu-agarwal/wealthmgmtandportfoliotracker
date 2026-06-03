package com.wealth.insight.catalog;

import java.util.List;

/**
 * Immutable representation of a single entry in the canonical ticker catalog.
 *
 * <p>Loaded once at startup from {@code seed/seed-tickers.json} by
 * {@code TickerCatalogService}. Carries all fields needed for LLM grounding
 * (ticker, name, aliases, assetClass, quoteCurrency) but intentionally omits
 * {@code basePrice} — prices come from Redis (MarketDataService) only, never from
 * this catalog (design Property 2: Redis-only facts).
 *
 * @param ticker        canonical symbol as stored in the catalog (e.g., {@code BTC-USD},
 *                      {@code RELIANCE.NS}, {@code AAPL}, {@code USDCHF=X})
 * @param name          human-readable display name (e.g., {@code "Bitcoin"}, {@code "Apple"})
 * @param aliases       list of additional user-facing names/abbreviations the LLM
 *                      or users might reference (e.g., {@code ["Bitcoin", "BTC"]})
 * @param assetClass    asset class string as stored in the catalog
 *                      (e.g., {@code "US_EQUITY"}, {@code "NSE"}, {@code "CRYPTO"},
 *                      {@code "FOREX"})
 * @param quoteCurrency ISO 4217 currency code for prices of this asset
 *                      (e.g., {@code "USD"}, {@code "INR"}, {@code "JPY"})
 */
public record CatalogEntry(
        String ticker,
        String name,
        List<String> aliases,
        String assetClass,
        String quoteCurrency
) {
    /**
     * Compact constructor that defensively copies the aliases list and validates
     * invariants required by the catalog integrity gate (Task 4).
     */
    public CatalogEntry {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("CatalogEntry.ticker must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CatalogEntry.name must not be blank for ticker: " + ticker);
        }
        if (assetClass == null || assetClass.isBlank()) {
            throw new IllegalArgumentException("CatalogEntry.assetClass must not be blank for ticker: " + ticker);
        }
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            throw new IllegalArgumentException("CatalogEntry.quoteCurrency must not be blank for ticker: " + ticker);
        }
        aliases = (aliases == null) ? List.of() : List.copyOf(aliases);
    }
}
