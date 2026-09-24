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
        LifecycleStatus lifecycleStatus,
        /*
         * The symbol the market-data provider quotes this asset under, when it differs from
         * {@code ticker} (e.g. Yahoo moved Uniswap from UNI-USD to UNI7083-USD). Absent means the
         * provider uses the ticker itself. Internal plumbing only: not part of the catalog version,
         * and holdings, prices and history stay keyed by {@code ticker}.
         */
        String providerSymbol) {}
