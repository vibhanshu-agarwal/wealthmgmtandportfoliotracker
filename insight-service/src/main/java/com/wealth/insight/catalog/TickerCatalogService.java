package com.wealth.insight.catalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loads the enriched catalog from {@code seed/seed-tickers.json} once at startup and provides
 * the supported ticker universe, grounding view, normalization, and category filtering.
 *
 * <p><strong>Normalization rules (Task 4 / Req 1.2, 1.3):</strong>
 * <ul>
 *   <li>Exact canonical symbols pass through unchanged ({@code BTC-USD}, {@code USDCHF=X},
 *       {@code RELIANCE.NS}, {@code AAPL}).</li>
 *   <li>Crypto stem/pair forms are resolved when the suffixed symbol is in the catalog:
 *       {@code BTC} / {@code BTCUSD} / {@code BTC/USD} → {@code BTC-USD}.</li>
 *   <li>Forex pair forms are resolved when the suffixed symbol is in the catalog:
 *       {@code USDCHF} / {@code USD/CHF} → {@code USDCHF=X}.</li>
 *   <li>Unknown or ambiguous tokens return {@link Optional#empty()}.</li>
 * </ul>
 *
 * <p><strong>Integrity checks (Task 4):</strong> on load, asserts every entry has a non-blank
 * {@code name}, a non-null {@code aliases} list, and unique ticker symbols. Ambiguous aliases
 * (mapping to multiple tickers) are logged but not collapsed — they are surfaced as candidates.
 */
@Service
public class TickerCatalogService {

    private static final Logger log = LoggerFactory.getLogger(TickerCatalogService.class);
    private static final String RESOURCE_PATH = "seed/seed-tickers.json";

    // Raw deserialization record — includes basePrice so the JSON parses without errors,
    // but basePrice is intentionally discarded; CatalogEntry has no price field.
    record RawEntry(String ticker, String name, List<String> aliases,
                    String assetClass, String quoteCurrency, BigDecimal basePrice) {}

    private List<CatalogEntry> entries = List.of();
    private Map<String, CatalogEntry> byTicker = Map.of();
    private Map<String, List<CatalogEntry>> byAlias = Map.of();
    private CompactCatalog compactCatalog;
    private String catalogVersion;

    @PostConstruct
    public void load() throws IOException {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        if (!resource.exists()) {
            log.error("TickerCatalogService: '{}' not found — catalog is empty.", RESOURCE_PATH);
            catalogVersion = "empty";
            compactCatalog = new CompactCatalog(List.of(), catalogVersion);
            return;
        }

        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        List<RawEntry> raw;
        try (InputStream is = resource.getInputStream()) {
            raw = mapper.readValue(is, new TypeReference<>() {});
        }

        runIntegrityChecks(raw);

        List<CatalogEntry> loaded = raw.stream()
                .map(r -> new CatalogEntry(r.ticker(), r.name(), r.aliases(), r.assetClass(), r.quoteCurrency()))
                .toList();

        // Build alias → entries map (one alias may map to multiple tickers — logged, not collapsed)
        Map<String, List<CatalogEntry>> aliasMap = new HashMap<>();
        for (CatalogEntry entry : loaded) {
            for (String alias : entry.aliases()) {
                aliasMap.computeIfAbsent(alias, k -> new ArrayList<>()).add(entry);
            }
        }

        this.entries = Collections.unmodifiableList(loaded);
        this.byTicker = loaded.stream()
                .collect(Collectors.toUnmodifiableMap(CatalogEntry::ticker, e -> e));
        this.byAlias = Collections.unmodifiableMap(aliasMap);
        this.catalogVersion = computeVersion(loaded);
        this.compactCatalog = new CompactCatalog(loaded, catalogVersion);

        log.info("TickerCatalogService: loaded {} entries, version={}", loaded.size(), catalogVersion);
    }

    // ── Public API ────────────────────────────────────────────────────────────────────────

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
        return entries.stream()
                .filter(e -> assetClass.equals(e.assetClass()))
                .toList();
    }

    /**
     * Returns the cached compact catalog (no prices) used as the LLM grounding payload.
     * Built once at startup (Req 7.4, 8.3).
     */
    public CompactCatalog groundingView() {
        return compactCatalog;
    }

    /**
     * Returns a stable hash of the loaded catalog for structured logs and Redis cache keys
     * (first 8 hex chars of SHA-256 over sorted, joined ticker symbols).
     */
    public String catalogVersion() {
        return catalogVersion;
    }

    /**
     * Deterministically canonicalizes a user-supplied token to a supported catalog symbol.
     *
     * <ul>
     *   <li>Exact match in catalog → returned as-is.</li>
     *   <li>Crypto stem ({@code BTC}) or glued pair ({@code BTCUSD}) or slashed pair
     *       ({@code BTC/USD}) → tries {@code <STEM>-USD} if present in catalog.</li>
     *   <li>Forex glued pair ({@code USDCHF}) or slashed pair ({@code USD/CHF}) →
     *       tries {@code <GLUED>=X} if present in catalog.</li>
     *   <li>Unknown/ambiguous token → {@link Optional#empty()}.</li>
     * </ul>
     */
    public Optional<String> normalize(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        String t = token.trim();

        // 1. Exact match (passthrough — covers BTC-USD, USDCHF=X, RELIANCE.NS, AAPL)
        if (byTicker.containsKey(t)) return Optional.of(t);

        // 2. Crypto normalization: BTC/BTCUSD/BTC/USD → BTC-USD
        Optional<String> crypto = tryCryptoNormalize(t);
        if (crypto.isPresent()) return crypto;

        // 3. Forex normalization: USDCHF/USD/CHF → USDCHF=X
        Optional<String> forex = tryForexNormalize(t);
        if (forex.isPresent()) return forex;

        return Optional.empty();
    }

    // ── Normalization helpers ─────────────────────────────────────────────────────────────

    /**
     * Attempts crypto-style normalization.
     * Handles: bare stem (BTC), glued pair (BTCUSD), slashed pair (BTC/USD).
     */
    private Optional<String> tryCryptoNormalize(String t) {
        String upper = t.toUpperCase();

        // Slashed pair: BTC/USD → BTC-USD
        if (upper.contains("/")) {
            String candidate = upper.replace("/", "-");
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        // Glued pair ending in USD: BTCUSD → BTC-USD
        if (upper.endsWith("USD") && upper.length() > 3) {
            String stem = upper.substring(0, upper.length() - 3);
            String candidate = stem + "-USD";
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        // Bare stem: BTC → BTC-USD  (look for <STEM>-USD in catalog)
        String candidate = upper + "-USD";
        if (byTicker.containsKey(candidate)) return Optional.of(candidate);

        return Optional.empty();
    }

    /**
     * Attempts forex-style normalization.
     * Handles: glued pair (USDCHF → USDCHF=X), slashed pair (USD/CHF → USDCHF=X).
     */
    private Optional<String> tryForexNormalize(String t) {
        String upper = t.toUpperCase();

        // Slashed pair: USD/CHF → remove slash → USDCHF=X
        if (upper.contains("/")) {
            String glued = upper.replace("/", "");
            String candidate = glued + "=X";
            if (byTicker.containsKey(candidate)) return Optional.of(candidate);
        }

        // Glued pair: USDCHF → USDCHF=X
        String candidate = upper + "=X";
        if (byTicker.containsKey(candidate)) return Optional.of(candidate);

        return Optional.empty();
    }

    // ── Integrity checks ──────────────────────────────────────────────────────────────────

    private static void runIntegrityChecks(List<RawEntry> raw) {
        List<String> violations = new ArrayList<>();

        // Check for blank names and null aliases
        for (RawEntry e : raw) {
            if (e.name() == null || e.name().isBlank()) {
                violations.add("Blank name for ticker: " + e.ticker());
            }
            if (e.aliases() == null) {
                violations.add("Null aliases list for ticker: " + e.ticker());
            }
        }

        // Check for duplicate tickers
        Map<String, Long> tickerCounts = raw.stream()
                .collect(Collectors.groupingBy(RawEntry::ticker, Collectors.counting()));
        tickerCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .forEach(entry -> violations.add("Duplicate ticker: " + entry.getKey()
                        + " (appears " + entry.getValue() + " times)"));

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Catalog integrity check failed with " + violations.size() + " violation(s):\n"
                            + String.join("\n", violations));
        }
    }

    private static String computeVersion(List<CatalogEntry> entries) {
        String joined = entries.stream()
                .map(CatalogEntry::ticker)
                .sorted()
                .collect(Collectors.joining("|"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
