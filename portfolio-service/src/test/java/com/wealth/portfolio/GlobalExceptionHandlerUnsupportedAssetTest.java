package com.wealth.portfolio;

import com.wealth.catalog.UnsupportedAssetException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerUnsupportedAssetTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsUnsupportedAssetTo422Contract() {
        UnsupportedAssetException ex = new UnsupportedAssetException("FAKE", "c3dcb95e4e09212a");

        ResponseEntity<Map<String, Object>> response = handler.handleUnsupportedAsset(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsEntry("error", "unsupported_asset")
                .containsEntry("ticker", "FAKE")
                .containsEntry("catalogVersion", "c3dcb95e4e09212a");
    }
}
