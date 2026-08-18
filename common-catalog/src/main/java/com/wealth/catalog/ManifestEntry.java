package com.wealth.catalog;

import java.math.BigDecimal;
import java.util.List;

record ManifestEntry(
        String ticker,
        String name,
        List<String> aliases,
        String assetClass,
        String quoteCurrency,
        BigDecimal basePrice,
        LifecycleStatus lifecycleStatus) {}
