package com.wealth.catalog;

import java.math.BigDecimal;
import java.util.Optional;

/** Seed-only view of base prices. Not reachable from validation or valuation paths. */
public interface SeedCatalogView {
    Optional<BigDecimal> basePrice(String ticker);
}
