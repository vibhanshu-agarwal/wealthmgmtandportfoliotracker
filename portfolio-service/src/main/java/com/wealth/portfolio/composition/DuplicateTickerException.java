package com.wealth.portfolio.composition;

import java.util.List;

public final class DuplicateTickerException extends RuntimeException {

    private final List<String> tickers;

    public DuplicateTickerException(List<String> tickers) {
        super("duplicate_ticker: " + tickers);
        this.tickers = List.copyOf(tickers);
    }

    public ContractErrorCode code() {
        return ContractErrorCode.duplicate_ticker;
    }

    /** Duplicate tickers in first-seen request order, deduplicated. */
    public List<String> tickers() {
        return tickers;
    }
}
