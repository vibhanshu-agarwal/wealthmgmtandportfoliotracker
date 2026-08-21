package com.wealth.portfolio.catalog;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.catalog.UnsupportedAssetException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedAssetValidatorTest {

    private static SupportedCatalog catalog;

    @BeforeAll
    static void loadCatalog() {
        catalog = SupportedCatalog.load();
    }

    @Test
    void whenEnforcementDisabled_unknownTickerIsAccepted() {
        SupportedAssetValidator validator = new SupportedAssetValidator(catalog, false);

        assertThatCode(() -> validator.requireActive("NOT_A_TICKER")).doesNotThrowAnyException();
    }

    @Test
    void requireActive_acceptsCanonicalActiveTicker() {
        SupportedAssetValidator validator = new SupportedAssetValidator(catalog, true);

        assertThatCode(() -> validator.requireActive("AAPL")).doesNotThrowAnyException();
    }

    @Test
    void requireActive_rejectsDeprecatedCanonicalTicker() {
        SupportedAssetValidator validator = new SupportedAssetValidator(catalog, true);

        assertThatThrownBy(() -> validator.requireActive("TATAMOTORS.NS"))
                .isInstanceOf(UnsupportedAssetException.class)
                .satisfies(
                        t -> {
                            UnsupportedAssetException ex = (UnsupportedAssetException) t;
                            assertThat(ex.ticker()).isEqualTo("TATAMOTORS.NS");
                            assertThat(ex.catalogVersion()).isEqualTo(catalog.version());
                        });
    }

    @Test
    void requireActive_doesNotResolveAliases() {
        SupportedAssetValidator validator = new SupportedAssetValidator(catalog, true);

        assertThatThrownBy(() -> validator.requireActive("Apple"))
                .isInstanceOf(UnsupportedAssetException.class)
                .extracting(t -> ((UnsupportedAssetException) t).ticker())
                .isEqualTo("Apple");
    }
}
