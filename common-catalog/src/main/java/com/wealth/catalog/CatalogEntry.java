package com.wealth.catalog;

import java.util.List;

public record CatalogEntry(
        String ticker,
        String name,
        List<String> aliases,
        String assetClass,
        String quoteCurrency,
        LifecycleStatus lifecycleStatus) {

    public CatalogEntry {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}
