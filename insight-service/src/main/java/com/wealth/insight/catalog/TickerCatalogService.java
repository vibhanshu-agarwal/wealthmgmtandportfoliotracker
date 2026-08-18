package com.wealth.insight.catalog;

import com.wealth.catalog.SupportedCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loads the enriched catalog from {@code catalog/seed-tickers.json} once at startup and provides
 * the supported ticker universe, grounding view, normalization, and category filtering.
 *
 * <p>Loading, integrity validation and versioning are delegated to {@link SupportedCatalog}.
 */
@Service
public class TickerCatalogService {

    private final List<CatalogEntry> entries;
    private final Map<String, CatalogEntry> byTicker;
    private final Map<String, List<CatalogEntry>> byAlias;
    private final CompactCatalog compactCatalog;
    private final String catalogVersion;

    public TickerCatalogService(SupportedCatalog supportedCatalog) {
        List<CatalogEntry> loaded =
                supportedCatalog.all().stream()
                        .map(
                                e ->
                                        new CatalogEntry(
                                                e.ticker(),
                                                e.name(),
                                                e.aliases(),
                                                e.assetClass(),
                                                e.quoteCurrency()))
                        .toList();

        Map<String, List<CatalogEntry>> aliasMap = new HashMap<>();
        for (CatalogEntry entry : loaded) {
            for (String alias : entry.aliases()) {
                aliasMap.computeIfAbsent(alias, k -> new ArrayList<>()).add(entry);
            }
        }

        this.entries = Collections.unmodifiableList(loaded);
        this.byTicker =
                loaded.stream()
                        .collect(Collectors.toUnmodifiableMap(CatalogEntry::ticker, e -> e));
        this.byAlias = Collections.unmodifiableMap(aliasMap);
        this.catalogVersion = supportedCatalog.version();
        this.compactCatalog = new CompactCatalog(loaded, catalogVersion);
    }

    /** Returns {@code true} if the ticker is in the supported catalog universe. */
    public boolean isSupported(String ticker) {
        return ticker != null && byTicker.containsKey(ticker);
    }

    /** Looks up a ticker by its canonical symbol. Returns empty if not found. */
    public Optional<CatalogEntry> find(String ticker) {
        if (ticker == null) return Optional.empty();
        return Optional.ofNullable(byTicker.get(ticker));
    }

    /** Returns all catalog entries with the given {@code assetClass} (case-sensitive). */
    public List<CatalogEntry> byCategory(String assetClass) {
        if (assetClass == null) return List.copyOf(entries);
        return entries.stream().filter(e -> assetClass.equals(e.assetClass())).toList();
    }

    /** Returns the cached compact catalog (no prices) used as the LLM grounding payload. */
    public CompactCatalog groundingView() {
        return compactCatalog;
    }

    /** Returns the catalog version hash shared across all catalog consumers. */
    public String catalogVersion() {
        return catalogVersion;
    }

    /**
     * Deterministically canonicalizes a user-supplied token to a supported catalog symbol.
     */
    public Optional<String> normalize(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        String t = token.trim();

        if (byTicker.containsKey(t)) return Optional.of(t);

        Optional<String> crypto = tryCryptoNormalize(t);
        if (crypto.isPresent()) return crypto;

        Optional<String> forex = tryForexNormalize(t);
        if (forex.isPresent()) return forex;

        return Optional.empty();
    }

    private Optional<String> tryCryptoNormalize(String t) {
        String upper = t.toUpperCase();

        if (upper.contains("/")) {
            String candidate = upper.replace("/", "-");
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        if (upper.endsWith("USD") && upper.length() > 3) {
            String stem = upper.substring(0, upper.length() - 3);
            String candidate = stem + "-USD";
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        String candidate = upper + "-USD";
        if (byTicker.containsKey(candidate)) return Optional.of(candidate);

        return Optional.empty();
    }

    private Optional<String> tryForexNormalize(String t) {
        String upper = t.toUpperCase();

        if (upper.contains("/")) {
            String glued = upper.replace("/", "");
            String candidate = glued + "=X";
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        String candidate = upper + "=X";
        if (byTicker.containsKey(candidate)) return Optional.of(candidate);

        return Optional.empty();
    }
}
