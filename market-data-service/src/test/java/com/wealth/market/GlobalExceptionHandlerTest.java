package com.wealth.market;

import com.wealth.catalog.UnsupportedAssetException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsUnsupportedAssetTo422Contract() {
        var response = handler.handleUnsupportedAsset(
                new UnsupportedAssetException("FAKE", "c3dcb95e4e09212a"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsEntry("error", "unsupported_asset")
                .containsEntry("ticker", "FAKE")
                .containsEntry("catalogVersion", "c3dcb95e4e09212a");
    }
}
