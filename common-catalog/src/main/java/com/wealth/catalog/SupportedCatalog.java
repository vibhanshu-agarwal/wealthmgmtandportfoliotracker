package com.wealth.catalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class SupportedCatalog implements SeedCatalogView {

    public static final String DEFAULT_RESOURCE_PATH = "catalog/seed-tickers.json";

    private final String resourcePath;
    private final String version;
    private final List<CatalogEntry> all;
    private final List<CatalogEntry> active;
    private final Map<String, CatalogEntry> byTicker;
    private final Map<String, BigDecimal> basePrices;

    private SupportedCatalog(
            String resourcePath,
            String version,
            List<CatalogEntry> all,
            Map<String, BigDecimal> basePrices) {
        this.resourcePath = resourcePath;
        this.version = version;
        this.all = List.copyOf(all);
        this.active =
                all.stream()
                        .filter(e -> e.lifecycleStatus() == LifecycleStatus.ACTIVE)
                        .toList();
        this.byTicker =
                all.stream()
                        .collect(Collectors.toUnmodifiableMap(CatalogEntry::ticker, e -> e));
        this.basePrices = Map.copyOf(basePrices);
        if (this.active.isEmpty()) {
            throw new CatalogLoadFailedException(
                    resourcePath, List.of("Catalog must contain at least one ACTIVE entry"));
        }
    }

    public static SupportedCatalog load() {
        return load(DEFAULT_RESOURCE_PATH);
    }

    public static SupportedCatalog load(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = SupportedCatalog.class.getClassLoader();
        }
        try (InputStream input = loader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new CatalogLoadFailedException(
                        resourcePath, List.of("Catalog resource not found on classpath"));
            }
            return load(input, resourcePath);
        } catch (IOException e) {
            throw new CatalogLoadFailedException(
                    resourcePath, "Catalog resource unreadable: " + e.getMessage());
        }
    }

    public static SupportedCatalog load(InputStream input, String resourcePath) {
        try {
            JsonMapper mapper =
                    JsonMapper.builder()
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .build();
            List<ManifestEntry> manifest =
                    mapper.readValue(input, new TypeReference<>() {});
            return fromManifest(manifest, resourcePath);
        } catch (CatalogLoadFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogLoadFailedException(
                    resourcePath, "Catalog resource unparseable: " + e.getMessage());
        }
    }

    static SupportedCatalog fromManifest(List<ManifestEntry> manifest, String resourcePath) {
        List<String> violations = CatalogIntegrityValidator.validate(manifest);
        if (!violations.isEmpty()) {
            throw new CatalogLoadFailedException(resourcePath, violations);
        }
        String version = CatalogVersion.compute(manifest);
        List<CatalogEntry> entries =
                manifest.stream()
                        .map(
                                m ->
                                        new CatalogEntry(
                                                m.ticker(),
                                                m.name(),
                                                m.aliases(),
                                                m.assetClass(),
                                                m.quoteCurrency(),
                                                m.lifecycleStatus()))
                        .toList();
        Map<String, BigDecimal> basePrices =
                manifest.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ManifestEntry::ticker, ManifestEntry::basePrice));
        return new SupportedCatalog(resourcePath, version, entries, basePrices);
    }

    public String resourcePath() {
        return resourcePath;
    }

    public List<CatalogEntry> all() {
        return all;
    }

    public List<CatalogEntry> active() {
        return active;
    }

    public Optional<CatalogEntry> find(String ticker) {
        if (ticker == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTicker.get(ticker));
    }

    public boolean isActive(String ticker) {
        return find(ticker).map(e -> e.lifecycleStatus() == LifecycleStatus.ACTIVE).orElse(false);
    }

    public List<CatalogEntry> byAssetClass(String assetClass) {
        if (assetClass == null) {
            return List.copyOf(all);
        }
        return all.stream().filter(e -> assetClass.equals(e.assetClass())).toList();
    }

    public String version() {
        return version;
    }

    @Override
    public Optional<BigDecimal> basePrice(String ticker) {
        if (ticker == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(basePrices.get(ticker));
    }
}
