package com.wealth.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CatalogIntegrityValidator {

    private CatalogIntegrityValidator() {}

    static List<String> validate(List<ManifestEntry> entries) {
        List<String> violations = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            violations.add("Catalog must contain at least one entry");
            return violations;
        }

        Map<String, Integer> tickerCounts = new HashMap<>();
        Map<String, Boolean> activeByClass = new HashMap<>();

        for (ManifestEntry entry : entries) {
            String ticker = entry.ticker();
            if (ticker == null || ticker.isBlank()) {
                violations.add("Blank ticker");
            } else {
                tickerCounts.merge(ticker, 1, Integer::sum);
            }

            if (entry.name() == null || entry.name().isBlank()) {
                violations.add("Blank name for ticker: " + safeTicker(ticker));
            }
            if (entry.aliases() == null) {
                violations.add("Null aliases list for ticker: " + safeTicker(ticker));
            }
            if (entry.assetClass() == null || entry.assetClass().isBlank()) {
                violations.add("Blank assetClass for ticker: " + safeTicker(ticker));
            }
            if (entry.quoteCurrency() == null || entry.quoteCurrency().isBlank()) {
                violations.add("Blank quoteCurrency for ticker: " + safeTicker(ticker));
            }
            if (entry.lifecycleStatus() == null) {
                violations.add("Absent lifecycleStatus for ticker: " + safeTicker(ticker));
            }
            if (entry.basePrice() == null) {
                violations.add("Null basePrice for ticker: " + safeTicker(ticker));
            } else if (entry.basePrice().compareTo(BigDecimal.ZERO) <= 0) {
                violations.add("Non-positive basePrice for ticker: " + safeTicker(ticker));
            }

            if (entry.assetClass() != null
                    && !entry.assetClass().isBlank()
                    && entry.lifecycleStatus() == LifecycleStatus.ACTIVE) {
                activeByClass.put(entry.assetClass(), true);
            }
        }

        tickerCounts.forEach((ticker, count) -> {
            if (count > 1) {
                violations.add("Duplicate ticker: " + ticker + " (appears " + count + " times)");
            }
        });

        Set<String> assetClasses = new HashSet<>();
        for (ManifestEntry entry : entries) {
            if (entry.assetClass() != null && !entry.assetClass().isBlank()) {
                assetClasses.add(entry.assetClass());
            }
        }
        for (String assetClass : assetClasses) {
            if (!activeByClass.getOrDefault(assetClass, false)) {
                violations.add("No ACTIVE entry for assetClass: " + assetClass);
            }
        }

        return violations;
    }

    private static String safeTicker(String ticker) {
        return ticker == null ? "<unknown>" : ticker;
    }
}
