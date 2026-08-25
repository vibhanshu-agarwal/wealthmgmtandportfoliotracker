package com.wealth.portfolio.composition;

import java.util.List;

public final class LifecycleNotPermittedException extends RuntimeException {

    private final List<String> tickers;
    private final String catalogVersion;

    public LifecycleNotPermittedException(List<String> tickers, String catalogVersion) {
        super("lifecycle_not_permitted: " + tickers + " catalogVersion=" + catalogVersion);
        this.tickers = List.copyOf(tickers);
        this.catalogVersion = catalogVersion;
    }

    public ContractErrorCode code() {
        return ContractErrorCode.lifecycle_not_permitted;
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