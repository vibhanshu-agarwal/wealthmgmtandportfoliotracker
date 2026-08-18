package com.wealth.catalog;

/**
 * Typed rejection when a write names a ticker that is not an Active_Asset.
 *
 * <p>Http_Entry_Points map this to HTTP 422 {@code unsupported_asset}. Direct callers
 * observe the same type with no HTTP semantics.
 */
public final class UnsupportedAssetException extends RuntimeException {

    private final String ticker;
    private final String catalogVersion;

    public UnsupportedAssetException(String ticker, String catalogVersion) {
        super("Unsupported asset: " + ticker + " (catalogVersion=" + catalogVersion + ")");
        this.ticker = ticker;
        this.catalogVersion = catalogVersion;
    }

    public String ticker() {
        return ticker;
    }

    public String catalogVersion() {
        return catalogVersion;
    }
}
