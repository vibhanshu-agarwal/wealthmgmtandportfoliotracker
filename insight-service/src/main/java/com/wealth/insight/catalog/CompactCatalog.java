package com.wealth.insight.catalog;

import java.util.List;

/**
 * Lightweight, price-free snapshot of the catalog, used as the grounding payload
 * sent to the LLM resolution client (Task 6 / Req 2.4).
 *
 * <p>Intentionally omits {@code basePrice} and any live price data — numbers must
 * only come from Redis (MarketDataService), never from the LLM prompt context
 * (design Property 2: Redis-only facts).
 *
 * <p>The {@code version} field is a stable hash of the loaded catalog produced by
 * {@code TickerCatalogService.catalogVersion()}, used in structured resolution logs
 * and as part of Redis cache keys (Req 9.1). It is included here so the LLM client
 * can embed it in logs without holding a reference to the full service.
 *
 * @param entries list of all supported {@link CatalogEntry} items (ticker, name,
 *                aliases, assetClass, quoteCurrency — no prices)
 * @param version stable catalog version hash (e.g. first 8 hex chars of SHA-256 of
 *                all tickers sorted and joined)
 */
public record CompactCatalog(
        List<CatalogEntry> entries,
        String version
) {
    /**
     * Compact constructor that defensively copies the entries list.
     */
    public CompactCatalog {
        if (entries == null) {
            throw new IllegalArgumentException("CompactCatalog.entries must not be null");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("CompactCatalog.version must not be blank");
        }
        entries = List.copyOf(entries);
    }
}
