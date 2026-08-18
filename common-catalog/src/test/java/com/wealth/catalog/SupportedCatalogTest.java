package com.wealth.catalog;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedCatalogTest {

    private static final String MINIMAL_ACTIVE = """
            [
              {"ticker":"AAPL","name":"Apple","aliases":["Apple"],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"BTC-USD","name":"Bitcoin","aliases":["BTC"],"assetClass":"CRYPTO","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"RELIANCE.NS","name":"Reliance","aliases":["Reliance"],"assetClass":"NSE","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"USDINR=X","name":"USD/INR","aliases":["USDINR"],"assetClass":"FOREX","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"}
            ]
            """;

    @Test
    void loadsValidManifestAndComputesVersion() {
        SupportedCatalog catalog = load(MINIMAL_ACTIVE);
        assertThat(catalog.all()).hasSize(4);
        assertThat(catalog.active()).hasSize(4);
        assertThat(catalog.version()).hasSize(16);
        assertThat(catalog.seedView().basePrice("AAPL")).contains(new BigDecimal("1.0"));
    }

    @Test
    void changingOnlyBasePriceChangesVersion() {
        String bumped = MINIMAL_ACTIVE.replaceFirst("\"basePrice\":1.0", "\"basePrice\":1.1");
        SupportedCatalog baseline = load(MINIMAL_ACTIVE);
        SupportedCatalog changed = load(bumped);
        assertThat(changed.version()).isNotEqualTo(baseline.version());
    }

    @Test
    void changingOnlyLifecycleStatusChangesVersion() {
        String baselineJson = """
            [
              {"ticker":"AAPL","name":"Apple","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"MSFT","name":"Microsoft","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"BTC-USD","name":"Bitcoin","aliases":[],"assetClass":"CRYPTO","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"RELIANCE.NS","name":"Reliance","aliases":[],"assetClass":"NSE","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"USDINR=X","name":"USD/INR","aliases":[],"assetClass":"FOREX","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"}
            ]
            """;
        String deprecatedAapl = baselineJson.replace(
                "\"ticker\":\"AAPL\",\"name\":\"Apple\",\"aliases\":[],\"assetClass\":\"US_EQUITY\",\"quoteCurrency\":\"USD\",\"basePrice\":1.0,\"lifecycleStatus\":\"ACTIVE\"",
                "\"ticker\":\"AAPL\",\"name\":\"Apple\",\"aliases\":[],\"assetClass\":\"US_EQUITY\",\"quoteCurrency\":\"USD\",\"basePrice\":1.0,\"lifecycleStatus\":\"DEPRECATED\"");
        SupportedCatalog baseline = load(baselineJson);
        SupportedCatalog changed = load(deprecatedAapl);
        assertThat(changed.version()).isNotEqualTo(baseline.version());
        assertThat(changed.isActive("AAPL")).isFalse();
        assertThat(changed.isActive("MSFT")).isTrue();
    }

    @Test
    void doesNotEnforceFixedTotalOrPerClassCounts() {
        String twoEquities = """
            [
              {"ticker":"AAPL","name":"Apple","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"MSFT","name":"Microsoft","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"BTC-USD","name":"Bitcoin","aliases":[],"assetClass":"CRYPTO","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"RELIANCE.NS","name":"Reliance","aliases":[],"assetClass":"NSE","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"USDINR=X","name":"USD/INR","aliases":[],"assetClass":"FOREX","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"}
            ]
            """;
        SupportedCatalog catalog = load(twoEquities);
        assertThat(catalog.byAssetClass("US_EQUITY")).hasSize(2);
    }

    @Test
    void collectsAllIntegrityViolationsBeforeThrowing() {
        String broken = """
            [
              {"ticker":"","name":"","aliases":null,"assetClass":"","quoteCurrency":"","basePrice":0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"AAPL","name":"Apple","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"}
            ]
            """;
        assertThatThrownBy(() -> load(broken))
                .isInstanceOf(CatalogLoadFailedException.class)
                .satisfies(
                        ex -> {
                            CatalogLoadFailedException failure = (CatalogLoadFailedException) ex;
                            assertThat(failure.violations()).hasSizeGreaterThan(1);
                        });
    }

    @Test
    void rejectsMissingResource() {
        assertThatThrownBy(() -> SupportedCatalog.load("catalog/does-not-exist.json"))
                .isInstanceOf(CatalogLoadFailedException.class);
    }

    @Test
    void activeFilterExcludesDeprecated() {
        String withDeprecated = """
            [
              {"ticker":"AAPL","name":"Apple","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"DEPRECATED"},
              {"ticker":"MSFT","name":"Microsoft","aliases":[],"assetClass":"US_EQUITY","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"BTC-USD","name":"Bitcoin","aliases":[],"assetClass":"CRYPTO","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"RELIANCE.NS","name":"Reliance","aliases":[],"assetClass":"NSE","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"},
              {"ticker":"USDINR=X","name":"USD/INR","aliases":[],"assetClass":"FOREX","quoteCurrency":"INR","basePrice":1.0,"lifecycleStatus":"ACTIVE"}
            ]
            """;
        SupportedCatalog catalog = load(withDeprecated);
        assertThat(catalog.all()).hasSize(5);
        assertThat(catalog.active()).hasSize(4);
        assertThat(catalog.isActive("AAPL")).isFalse();
    }

    private static SupportedCatalog load(String json) {
        return SupportedCatalog.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.json");
    }
}
