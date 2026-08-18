package com.wealth.market.seed;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SeedTickerRegistryTest {

    private SeedTickerRegistry registry;

    @BeforeEach
    void setUp() {
        SupportedCatalog catalog = SupportedCatalog.load();
        registry = new SeedTickerRegistry(catalog, catalog.seedView());
        assumeTrue(!registry.all().isEmpty(),
                "catalog/seed-tickers.json not found on classpath – run ./gradlew :market-data-service:processResources");
    }

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

    @Test
    void load_mahindraTickerUsesCorrectSymbol() {
        assertThat(registry.find("MM.NS")).isPresent();
        assertThat(registry.find("M&M.NS")).isEmpty();
    }

    @Test
    void load_coreFieldsPreserved() {
        Optional<SeedTicker> reliance = registry.find("RELIANCE.NS");
        assertThat(reliance).isPresent();
        assertThat(reliance.get().quoteCurrency()).isEqualTo("INR");
        assertThat(reliance.get().basePrice()).isNotNull();
    }
}
