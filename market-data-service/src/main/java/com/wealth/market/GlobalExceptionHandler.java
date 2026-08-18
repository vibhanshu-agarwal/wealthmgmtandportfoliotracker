package com.wealth.market;

import com.wealth.catalog.UnsupportedAssetException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnsupportedAssetException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedAsset(UnsupportedAssetException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "unsupported_asset",
                "ticker", ex.ticker(),
                "catalogVersion", ex.catalogVersion()));
    }
}
