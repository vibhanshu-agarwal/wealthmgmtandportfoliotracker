package com.wealth.portfolio.composition;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.LifecycleStatus;
import com.wealth.catalog.SupportedCatalog;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Catalog resolution and lifecycle permission for composition (task 4.5). Canonical tickers only.
 * Aggregates every offender within each 422 class in request order.
 */
@Component
public class CompositionCatalogValidator {

    private final SupportedCatalog catalog;

    public CompositionCatalogValidator(SupportedCatalog catalog) {
        this.catalog = catalog;
    }

    public void validate(List<RawIntent> intent, List<HoldingSnapshot> lockedSnapshot) {
        Map<String, BigDecimal> heldQty =
                lockedSnapshot.stream()
                        .collect(
                                Collectors.toMap(
                                        HoldingSnapshot::ticker,
                                        HoldingSnapshot::quantity,
                                        (a, b) -> a));

        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        LinkedHashSet<String> lifecycle = new LinkedHashSet<>();

        for (RawIntent item : intent) {
            String ticker = item.ticker();
            Optional<CatalogEntry> entry = catalog.find(ticker);
            if (entry.isEmpty()) {
                unsupported.add(ticker);
                continue;
            }
            if (entry.get().lifecycleStatus() == LifecycleStatus.ACTIVE) {
                continue;
            }
            BigDecimal current = heldQty.get(ticker);
            boolean introducing = current == null;
            boolean increasing =
                    !introducing && item.quantity().compareTo(current) > 0;
            if (introducing || increasing) {
                lifecycle.add(ticker);
            }
        }

        if (!unsupported.isEmpty()) {
            throw new UnsupportedAssetsException(List.copyOf(unsupported), catalog.version());
        }
        if (!lifecycle.isEmpty()) {
            throw new LifecycleNotPermittedException(List.copyOf(lifecycle), catalog.version());
        }
    }
}
