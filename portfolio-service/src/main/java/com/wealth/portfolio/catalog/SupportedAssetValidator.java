package com.wealth.portfolio.catalog;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.catalog.UnsupportedAssetException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-process write-boundary check against the catalog. Matches canonical ticker only;
 * no alias resolution and no cross-service call.
 *
 * <p>Gated by {@code app.catalog.enforce-holding-invariant} (default {@code false}).
 */
@Component
public class SupportedAssetValidator {

    private final SupportedCatalog catalog;
    private final boolean enforceHoldingInvariant;

    public SupportedAssetValidator(
            SupportedCatalog catalog,
            @Value("${app.catalog.enforce-holding-invariant:false}") boolean enforceHoldingInvariant) {
        this.catalog = catalog;
        this.enforceHoldingInvariant = enforceHoldingInvariant;
    }

    public void requireActive(String ticker) {
        if (!enforceHoldingInvariant) {
            return;
        }
        if (!catalog.isActive(ticker)) {
            throw new UnsupportedAssetException(ticker, catalog.version());
        }
    }
}
