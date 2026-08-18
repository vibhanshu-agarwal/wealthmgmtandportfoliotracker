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
        assertThat(catalog.find("MM.NS"))
                .isPresent();
        assertThat(catalog.find("M&M.NS")).isEmpty();
        assertThat(catalog.find("TATAMOTORS.NS"))
                .isPresent()
                .get()
                .extracting(CatalogEntry::lifecycleStatus)
                .isEqualTo(LifecycleStatus.DEPRECATED);
        assertThat(catalog.version()).hasSize(16);
    }
}
