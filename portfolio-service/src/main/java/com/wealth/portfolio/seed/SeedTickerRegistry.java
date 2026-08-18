package com.wealth.portfolio.seed;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.SeedCatalogView;
import com.wealth.catalog.SupportedCatalog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Portfolio-service view of the canonical supported-asset catalog.
 *
 * <p>Loading, integrity validation and versioning are delegated to {@link SupportedCatalog}.
 */
@Component
public class SeedTickerRegistry {

    /**
     * Represents one catalog entry including seed-only {@code basePrice}.
     */
    public record SeedTicker(
            String ticker,
            String assetClass,
            String quoteCurrency,
            BigDecimal basePrice,
            String name,
            List<String> aliases) {}

    private final List<SeedTicker> tickers;
    private final Map<String, SeedTicker> byTicker;

    private final List<SeedTicker> activeTickers;

    public SeedTickerRegistry(SupportedCatalog catalog, SeedCatalogView seedView) {
        this.tickers =
                catalog.all().stream().map(entry -> toSeedTicker(entry, seedView)).toList();
        this.activeTickers =
                catalog.active().stream().map(entry -> toSeedTicker(entry, seedView)).toList();
        this.byTicker =
                tickers.stream()
                        .collect(Collectors.toUnmodifiableMap(SeedTicker::ticker, ticker -> ticker));
    }

    public List<SeedTicker> all() {
        return tickers;
    }

    /** Seed paths enumerate Active_Assets only. Lookup via {@link #find} still covers deprecated. */
    public List<SeedTicker> active() {
        return activeTickers;
    }

    public Optional<SeedTicker> find(String ticker) {
        return Optional.ofNullable(byTicker.get(ticker));
    }

    private static SeedTicker toSeedTicker(CatalogEntry entry, SeedCatalogView seedView) {
        BigDecimal basePrice =
                seedView.basePrice(entry.ticker())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing basePrice for ticker: " + entry.ticker()));
        return new SeedTicker(
                entry.ticker(),
                entry.assetClass(),
                entry.quoteCurrency(),
                basePrice,
                entry.name(),
                entry.aliases());
    }
}
