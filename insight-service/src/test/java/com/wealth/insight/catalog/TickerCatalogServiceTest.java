package com.wealth.insight.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deterministic unit tests for {@link TickerCatalogService} (Task 4 / Req 1.2, 1.3, 1.4,
 * 1.6, 7.4, 7.5, 8.3).
 *
 * <p>Covers normalization edge cases, {@code isSupported}, {@code byCategory} filtering,
 * {@code catalogVersion} stability, and catalog integrity on load.
 *
 * <p>Tests are skipped when {@code seed/seed-tickers.json} is not on the classpath.
 * Run via {@code ./gradlew :insight-service:test} which triggers {@code copySeedTickers}.
 */
class TickerCatalogServiceTest {

    private TickerCatalogService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TickerCatalogService();
        service.load();
        assumeTrue(service.isSupported("AAPL"),
                "seed/seed-tickers.json not on classpath – run ./gradlew copySeedTickers first");
    }

    // ── normalize: exact passthrough ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "exact passthrough: {0}")
    @ValueSource(strings = {"AAPL", "MSFT", "BTC-USD", "USDCHF=X", "RELIANCE.NS",
                            "ETH-USD", "EURUSD=X", "HDFCBANK.NS"})
    void normalize_exactSymbol_passesThroughUnchanged(String ticker) {
        assertThat(service.normalize(ticker)).isPresent().hasValue(ticker);
    }

    // ── normalize: crypto stem (BTC → BTC-USD) ────────────────────────────────────────────

    @Test
    void normalize_cryptoBareStem_resolves() {
        assertThat(service.normalize("BTC")).isPresent().hasValue("BTC-USD");
        assertThat(service.normalize("ETH")).isPresent().hasValue("ETH-USD");
        assertThat(service.normalize("SOL")).isPresent().hasValue("SOL-USD");
    }

    // ── normalize: crypto glued pair (BTCUSD → BTC-USD) ──────────────────────────────────

    @Test
    void normalize_cryptoGluedPair_resolves() {
        assertThat(service.normalize("BTCUSD")).isPresent().hasValue("BTC-USD");
        assertThat(service.normalize("ETHUSD")).isPresent().hasValue("ETH-USD");
    }

    // ── normalize: crypto slashed pair (BTC/USD → BTC-USD) ───────────────────────────────

    @Test
    void normalize_cryptoSlashedPair_resolves() {
        assertThat(service.normalize("BTC/USD")).isPresent().hasValue("BTC-USD");
        assertThat(service.normalize("ETH/USD")).isPresent().hasValue("ETH-USD");
    }

    // ── normalize: forex glued pair (USDCHF → USDCHF=X) ─────────────────────────────────

    @Test
    void normalize_forexGluedPair_resolves() {
        assertThat(service.normalize("USDCHF")).isPresent().hasValue("USDCHF=X");
        assertThat(service.normalize("EURUSD")).isPresent().hasValue("EURUSD=X");
        assertThat(service.normalize("GBPUSD")).isPresent().hasValue("GBPUSD=X");
    }

    // ── normalize: forex slashed pair (USD/CHF → USDCHF=X) ──────────────────────────────

    @Test
    void normalize_forexSlashedPair_resolves() {
        assertThat(service.normalize("USD/CHF")).isPresent().hasValue("USDCHF=X");
        assertThat(service.normalize("EUR/USD")).isPresent().hasValue("EURUSD=X");
    }

    // ── normalize: unknown token → empty ─────────────────────────────────────────────────

    @ParameterizedTest(name = "unknown token: {0}")
    @ValueSource(strings = {"FAKECOIN", "XYZABC", "BANANA", "DOESNOTEXIST", "UNKNOWN-USD"})
    void normalize_unknownToken_returnsEmpty(String token) {
        assertThat(service.normalize(token)).isEmpty();
    }

    @Test
    void normalize_nullToken_returnsEmpty() {
        assertThat(service.normalize(null)).isEmpty();
    }

    @Test
    void normalize_blankToken_returnsEmpty() {
        assertThat(service.normalize("  ")).isEmpty();
    }

    // ── isSupported ───────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "isSupported=true: {0}")
    @ValueSource(strings = {"AAPL", "BTC-USD", "USDCHF=X", "RELIANCE.NS", "HDFCBANK.NS"})
    void isSupported_knownTicker_returnsTrue(String ticker) {
        assertThat(service.isSupported(ticker)).isTrue();
    }

    @ParameterizedTest(name = "isSupported=false: {0}")
    @ValueSource(strings = {"FAKECOIN", "BTC", "USDCHF", "RELIANCE", "Apple"})
    void isSupported_unknownOrUnsuffixedToken_returnsFalse(String token) {
        assertThat(service.isSupported(token)).isFalse();
    }

    @Test
    void isSupported_null_returnsFalse() {
        assertThat(service.isSupported(null)).isFalse();
    }

    // ── byCategory filtering ──────────────────────────────────────────────────────────────

    @Test
    void byCategory_usEquity_returns50Entries() {
        assertThat(service.byCategory("US_EQUITY")).hasSize(50);
    }

    @Test
    void byCategory_nse_returns50Entries() {
        assertThat(service.byCategory("NSE")).hasSize(50);
    }

    @Test
    void byCategory_crypto_returns50Entries() {
        assertThat(service.byCategory("CRYPTO")).hasSize(50);
    }

    @Test
    void byCategory_forex_returns10Entries() {
        assertThat(service.byCategory("FOREX")).hasSize(10);
    }

    @Test
    void byCategory_null_returnsAllEntries() {
        assertThat(service.byCategory(null)).hasSize(160);
    }

    @Test
    void byCategory_unknown_returnsEmpty() {
        assertThat(service.byCategory("UNKNOWN_ASSET_CLASS")).isEmpty();
    }

    // ── catalogVersion stability ──────────────────────────────────────────────────────────

    @Test
    void catalogVersion_isStableAcrossMultipleCalls() {
        String v1 = service.catalogVersion();
        String v2 = service.catalogVersion();
        assertThat(v1).isEqualTo(v2);
    }

    @Test
    void catalogVersion_isEightCharHex() {
        assertThat(service.catalogVersion()).matches("[0-9a-f]{8}");
    }

    // ── groundingView ─────────────────────────────────────────────────────────────────────

    @Test
    void groundingView_hasAllEntries() {
        CompactCatalog catalog = service.groundingView();
        assertThat(catalog.entries()).hasSize(160);
        assertThat(catalog.version()).isEqualTo(service.catalogVersion());
    }

    // ── Catalog integrity: name and aliases populated ─────────────────────────────────────

    @Test
    void catalogIntegrity_allEntriesHaveNonBlankName() {
        List<CatalogEntry> bad = service.byCategory(null).stream()
                .filter(e -> e.name() == null || e.name().isBlank())
                .toList();
        assertThat(bad).as("all catalog entries must have a non-blank name").isEmpty();
    }

    @Test
    void catalogIntegrity_allEntriesHaveNonNullAliasesList() {
        List<CatalogEntry> bad = service.byCategory(null).stream()
                .filter(e -> e.aliases() == null)
                .toList();
        assertThat(bad).as("all catalog entries must have a non-null aliases list").isEmpty();
    }

    @Test
    void find_knownTicker_returnsEntryWithNameAndAliases() {
        Optional<CatalogEntry> btc = service.find("BTC-USD");
        assertThat(btc).isPresent();
        assertThat(btc.get().name()).isEqualTo("Bitcoin");
        assertThat(btc.get().aliases()).contains("Bitcoin", "BTC");
    }

    @Test
    void find_unknownTicker_returnsEmpty() {
        assertThat(service.find("DOES_NOT_EXIST")).isEmpty();
    }
}
