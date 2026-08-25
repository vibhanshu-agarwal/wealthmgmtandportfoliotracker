package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.LifecycleStatus;
import com.wealth.catalog.SupportedCatalog;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompositionCatalogValidatorTest {

    private CompositionCatalogValidator validator;
    private SupportedCatalog catalog;
    private String deprecatedTicker;
    private String activeTicker;
    private String catalogVersion;

    @BeforeEach
    void setUp() {
        catalog = SupportedCatalog.load();
        catalogVersion = catalog.version();
        activeTicker = catalog.active().getFirst().ticker();
        deprecatedTicker =
                catalog.all().stream()
                        .filter(e -> e.lifecycleStatus() == LifecycleStatus.DEPRECATED)
                        .map(CatalogEntry::ticker)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("catalog needs a deprecated asset"));
        validator = new CompositionCatalogValidator(catalog);
    }

    @Test
    void aggregatesUnknownTickersInRequestOrder() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        List.of(
                                                new RawIntent("ZZZ_UNKNOWN", new BigDecimal("1")),
                                                new RawIntent(activeTicker, new BigDecimal("1")),
                                                new RawIntent("AAA_UNKNOWN", new BigDecimal("1")),
                                                new RawIntent("ZZZ_UNKNOWN", new BigDecimal("2"))),
                                        List.of()))
                .isInstanceOf(UnsupportedAssetsException.class)
                .satisfies(
                        ex -> {
                            UnsupportedAssetsException u = (UnsupportedAssetsException) ex;
                            assertThat(u.tickers())
                                    .containsExactly("ZZZ_UNKNOWN", "AAA_UNKNOWN");
                            assertThat(u.catalogVersion()).isEqualTo(catalogVersion);
                        });
    }

    @Test
    void rejectsIntroducingDeprecatedAsset() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        List.of(
                                                new RawIntent(
                                                        deprecatedTicker, new BigDecimal("1"))),
                                        List.of()))
                .isInstanceOf(LifecycleNotPermittedException.class)
                .satisfies(
                        ex ->
                                assertThat(((LifecycleNotPermittedException) ex).tickers())
                                        .containsExactly(deprecatedTicker));
    }

    @Test
    void rejectsIncreasingRetainedDeprecatedPosition() {
        HoldingSnapshot held =
                new HoldingSnapshot(
                        deprecatedTicker, new BigDecimal("2"), null, null, null, null);

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        List.of(
                                                new RawIntent(
                                                        deprecatedTicker, new BigDecimal("3"))),
                                        List.of(held)))
                .isInstanceOf(LifecycleNotPermittedException.class);
    }

    @Test
    void permitsRetainReduceAndRemoveOfDeprecatedPosition() {
        HoldingSnapshot held =
                new HoldingSnapshot(
                        deprecatedTicker, new BigDecimal("5"), null, null, null, null);

        validator.validate(
                List.of(new RawIntent(deprecatedTicker, new BigDecimal("5"))), List.of(held));
        validator.validate(
                List.of(new RawIntent(deprecatedTicker, new BigDecimal("2"))), List.of(held));
        validator.validate(List.of(), List.of(held));
    }

    @Test
    void permitsActiveCreateChangeRetainRemove() {
        validator.validate(
                List.of(new RawIntent(activeTicker, new BigDecimal("1"))), List.of());
        HoldingSnapshot held =
                new HoldingSnapshot(activeTicker, new BigDecimal("1"), null, null, null, null);
        validator.validate(
                List.of(new RawIntent(activeTicker, new BigDecimal("9"))), List.of(held));
        validator.validate(List.of(), List.of(held));
    }

    @Test
    void catalogVersionChangeOnUnreferencedTickerDoesNotBlockValidComposition() throws Exception {
        String jsonA =
                """
                [
                  {"ticker":"AAA","name":"A","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":10.0,"lifecycleStatus":"ACTIVE"},
                  {"ticker":"ZZZ","name":"Z","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"DEPRECATED"}
                ]
                """;
        String jsonB =
                """
                [
                  {"ticker":"AAA","name":"A","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":10.0,"lifecycleStatus":"ACTIVE"},
                  {"ticker":"ZZZ","name":"Z","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":99.0,"lifecycleStatus":"DEPRECATED"}
                ]
                """;
        SupportedCatalog catalogA =
                SupportedCatalog.load(
                        new java.io.ByteArrayInputStream(jsonA.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "test-a.json");
        SupportedCatalog catalogB =
                SupportedCatalog.load(
                        new java.io.ByteArrayInputStream(jsonB.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "test-b.json");
        assertThat(catalogA.version()).isNotEqualTo(catalogB.version());

        CompositionCatalogValidator validatorB = new CompositionCatalogValidator(catalogB);
        validatorB.validate(List.of(new RawIntent("AAA", new BigDecimal("1"))), List.of());
    }

    @Test
    void multipleLifecycleOffendersAggregateInRequestOrder() {
        String json =
                """
                [
                  {"ticker":"AAA","name":"A","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":10.0,"lifecycleStatus":"ACTIVE"},
                  {"ticker":"DEP1","name":"D1","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":1.0,"lifecycleStatus":"DEPRECATED"},
                  {"ticker":"DEP2","name":"D2","aliases":[],"assetClass":"EQ","quoteCurrency":"USD","basePrice":2.0,"lifecycleStatus":"DEPRECATED"}
                ]
                """;
        SupportedCatalog multi =
                SupportedCatalog.load(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "multi-deprecated.json");
        CompositionCatalogValidator multiValidator = new CompositionCatalogValidator(multi);

        assertThatThrownBy(
                        () ->
                                multiValidator.validate(
                                        List.of(
                                                new RawIntent("AAA", new BigDecimal("1")),
                                                new RawIntent("DEP2", new BigDecimal("1")),
                                                new RawIntent("DEP1", new BigDecimal("2"))),
                                        List.of()))
                .isInstanceOf(LifecycleNotPermittedException.class)
                .satisfies(
                        ex ->
                                assertThat(((LifecycleNotPermittedException) ex).tickers())
                                        .containsExactly("DEP2", "DEP1"));
    }
}
