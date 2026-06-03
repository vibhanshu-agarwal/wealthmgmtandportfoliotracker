package com.wealth.market.seed;

import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
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
 * {@code seed/seed-tickers.json} (with {@code name}/{@code aliases} fields) loads correctly,
 * that the 160-entry count and asset-class distribution are preserved, and that the
 * optional {@code name}/{@code aliases} fields are populated (Task 2 / Req 7.3, 9.5).
 *
 * <p>The test is skipped automatically when {@code seed/seed-tickers.json} is not on the
 * classpath (e.g. in IDE runs before {@code copySeedTickers} has executed).
 * Run via {@code ./gradlew :market-data-service:test} to guarantee the file is present.
 */
class SeedTickerRegistryTest {

    private SeedTickerRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SeedTickerRegistry();
        registry.load();
        // Skip the test suite if seed-tickers.json is absent (IDE run without copySeedTickers).
        assumeTrue(!registry.all().isEmpty(),
                "seed/seed-tickers.json not found on classpath – run ./gradlew copySeedTickers first");
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
