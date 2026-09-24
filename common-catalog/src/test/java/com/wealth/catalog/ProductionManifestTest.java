package com.wealth.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionManifestTest {

    @Test
    void repoManifestLoadsWith160EntriesAnd159Active() throws Exception {
        Path manifest = Path.of("../config/seed-tickers.json");
        SupportedCatalog catalog =
                SupportedCatalog.load(
                        Files.newInputStream(manifest), manifest.toString().replace('\\', '/'));

        assertThat(catalog.all()).hasSize(160);
        assertThat(catalog.active()).hasSize(159);
        assertThat(catalog.find("M&M.NS"))
                .isPresent()
                .get()
                .extracting(CatalogEntry::lifecycleStatus)
                .isEqualTo(LifecycleStatus.ACTIVE);
        assertThat(catalog.find("MM.NS")).isEmpty();
        assertThat(catalog.find("TATAMOTORS.NS"))
                .isPresent()
                .get()
                .extracting(CatalogEntry::lifecycleStatus)
                .isEqualTo(LifecycleStatus.DEPRECATED);
        assertThat(catalog.version()).hasSize(16);
    }

    /**
     * Rehearsal defect #2: Yahoo quotes these assets under new symbols and serves other tokens
     * under the old ones. Each mapping was verified against Yahoo's own name and a recent price on
     * 2026-09-24 (handoff SYMBOL_VERIFICATION_2026-09-24.md, with the TON addendum); FTM-USD had no
     * verified replacement and is deliberately not mapped.
     */
    @Test
    void repoManifestMapsExactlyTheVerifiedProviderSymbols() throws Exception {
        Path manifest = Path.of("../config/seed-tickers.json");
        SupportedCatalog catalog =
                SupportedCatalog.load(
                        Files.newInputStream(manifest), manifest.toString().replace('\\', '/'));

        java.util.Map<String, String> mapped = new java.util.TreeMap<>();
        for (CatalogEntry e : catalog.all()) {
            if (!catalog.providerSymbol(e.ticker()).equals(e.ticker())) {
                mapped.put(e.ticker(), catalog.providerSymbol(e.ticker()));
            }
        }
        assertThat(mapped).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "MATIC-USD", "POL28321-USD",
                "UNI-USD", "UNI7083-USD",
                "APT-USD", "APT21794-USD",
                "IMX-USD", "IMX10603-USD",
                "GRT-USD", "GRT6719-USD",
                "ARB-USD", "ARB11841-USD",
                "TON-USD", "TON11419-USD"));
        assertThat(catalog.providerSymbol("FTM-USD")).isEqualTo("FTM-USD");
    }
}
