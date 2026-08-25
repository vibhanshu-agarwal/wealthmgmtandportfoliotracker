package com.wealth.portfolio.dto;

import com.wealth.catalog.LifecycleStatus;

import java.util.List;

/**
 * Read-only discovery projection for {@code GET /api/assets}. Prices and {@code basePrice} are
 * intentionally excluded (D11).
 */
public record AssetCatalogResponse(String catalogVersion, List<AssetEntry> assets) {

    public record AssetEntry(
            String ticker,
            String name,
            List<String> aliases,
            String assetClass,
            String quoteCurrency,
            LifecycleStatus lifecycleStatus
    ) {}
}
