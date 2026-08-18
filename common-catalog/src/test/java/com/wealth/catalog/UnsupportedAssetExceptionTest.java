package com.wealth.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnsupportedAssetExceptionTest {

    @Test
    void carriesTickerAndCatalogVersion() {
        UnsupportedAssetException ex = new UnsupportedAssetException("FAKE", "abcd1234abcd1234");

        assertThat(ex.ticker()).isEqualTo("FAKE");
        assertThat(ex.catalogVersion()).isEqualTo("abcd1234abcd1234");
        assertThat(ex.getMessage()).contains("FAKE").contains("abcd1234abcd1234");
    }
}
