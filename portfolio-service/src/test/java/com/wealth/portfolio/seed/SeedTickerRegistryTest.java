package com.wealth.portfolio.seed;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link SeedTickerRegistry} verifying that the enriched
 * {@code catalog/seed-tickers.json} loads correctly through {@link SupportedCatalog}.
 */
class SeedTickerRegistryTest {

    private SeedTickerRegistry registry;

    @BeforeEach
    void setUp() {
        SupportedCatalog catalog = SupportedCatalog.load();
        registry = new SeedTickerRegistry(catalog, catalog.seedView());
        assumeTrue(!registry.all().isEmpty(),
                "catalog/seed-tickers.json not found on classpath – run ./gradlew :portfolio-service:processResources");
    }

    // ── Count and distribution ─────────────────────────────────────────────────────────────

    @Test
    void load_totalCount_is160() {
        assertThat(registry.all()).hasSize(160);
    }

    @Test
    void load_assetClassDistribution_isCorrect() {
        Map<String, Long> counts = registry.all().stream()
                .collect(Collectors.groupingBy(SeedTicker::assetClass, Collectors.counting()));

        assertThat(counts.get("US_EQUITY")).isEqualTo(50L);
        assertThat(counts.get("NSE")).isEqualTo(50L);
        assertThat(counts.get("CRYPTO")).isEqualTo(50L);
        assertThat(counts.get("FOREX")).isEqualTo(10L);
    }

    // ── Enriched fields: US_EQUITY ─────────────────────────────────────────────────────────

    @Test
    void load_usEquity_nameAndAliasesPresent() {
        Optional<SeedTicker> aapl = registry.find("AAPL");
        assertThat(aapl).isPresent();
        assertThat(aapl.get().name()).isEqualTo("Apple");
        assertThat(aapl.get().aliases()).containsExactlyInAnyOrder("Apple", "Apple Inc");
    }

    @Test
    void load_berkshireHathaway_nameAndAliasesPresent() {
        Optional<SeedTicker> brk = registry.find("BRK-B");
        assertThat(brk).isPresent();
        assertThat(brk.get().name()).isEqualTo("Berkshire Hathaway");
        assertThat(brk.get().aliases()).contains("Berkshire", "Berkshire Hathaway");
    }

    // ── Enriched fields: NSE ───────────────────────────────────────────────────────────────

    @Test
    void load_nse_nameAndAliasesPresent() {
        Optional<SeedTicker> hdfc = registry.find("HDFCBANK.NS");
        assertThat(hdfc).isPresent();
        assertThat(hdfc.get().name()).isEqualTo("HDFC Bank");
        assertThat(hdfc.get().aliases()).contains("HDFC Bank", "HDFCBANK");
    }

    // ── Enriched fields: CRYPTO ────────────────────────────────────────────────────────────

    @Test
    void load_crypto_nameAndAliasesPresent() {
        Optional<SeedTicker> btc = registry.find("BTC-USD");
        assertThat(btc).isPresent();
        assertThat(btc.get().name()).isEqualTo("Bitcoin");
        assertThat(btc.get().aliases()).contains("Bitcoin", "BTC");
    }

    // ── Enriched fields: FOREX ─────────────────────────────────────────────────────────────

    @Test
    void load_forex_nameAndAliasesPresent() {
        Optional<SeedTicker> usdchf = registry.find("USDCHF=X");
        assertThat(usdchf).isPresent();
        assertThat(usdchf.get().name()).isEqualTo("USD/CHF");
        assertThat(usdchf.get().aliases()).contains("USDCHF", "USD/CHF");
    }

    // ── No-name entries must not exist (catalog integrity) ────────────────────────────────

    @Test
    void load_allEntries_haveNonNullName() {
        List<SeedTicker> noName = registry.all().stream()
                .filter(t -> t.name() == null || t.name().isBlank())
                .toList();
        assertThat(noName)
                .as("all entries must have a non-blank name after catalog enrichment")
                .isEmpty();
    }

    @Test
    void load_allEntries_haveNonNullAliasesList() {
        List<SeedTicker> noAliases = registry.all().stream()
                .filter(t -> t.aliases() == null)
                .toList();
        assertThat(noAliases)
                .as("all entries must have a non-null aliases list after catalog enrichment")
                .isEmpty();
    }

    @Test
    void load_mahindraTickerUsesCorrectSymbol() {
        assertThat(registry.find("MM.NS")).isPresent();
        assertThat(registry.find("M&M.NS")).isEmpty();
    }

    // ── Core seeding fields preserved ─────────────────────────────────────────────────────

    @Test
    void load_coreFieldsPreserved() {
        Optional<SeedTicker> reliance = registry.find("RELIANCE.NS");
        assertThat(reliance).isPresent();
        assertThat(reliance.get().ticker()).isEqualTo("RELIANCE.NS");
        assertThat(reliance.get().assetClass()).isEqualTo("NSE");
        assertThat(reliance.get().quoteCurrency()).isEqualTo("INR");
        assertThat(reliance.get().basePrice()).isNotNull();
    }
}
