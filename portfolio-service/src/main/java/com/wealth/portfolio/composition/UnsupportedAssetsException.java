package com.wealth.portfolio.composition;

import java.util.List;

/**
 * Plural catalog rejection for composition. Spec A's singular {@code UnsupportedAssetException}
 * remains untouched on its single-write path.
 */
public final class UnsupportedAssetsException extends RuntimeException {

    private final List<String> tickers;
    private final String catalogVersion;

    public UnsupportedAssetsException(List<String> tickers, String catalogVersion) {
        super("unsupported_asset: " + tickers + " catalogVersion=" + catalogVersion);
        this.tickers = List.copyOf(tickers);
        this.catalogVersion = catalogVersion;
    }

    public ContractErrorCode code() {
        return ContractErrorCode.unsupported_asset;
    }

    public List<String> tickers() {
        return tickers;
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public String firstTicker() {
        return tickers.getFirst();
    }
}
