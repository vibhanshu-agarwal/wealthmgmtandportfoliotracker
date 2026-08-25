package com.wealth.portfolio.composition;

import java.util.List;

public final class QuantityOutOfDomainException extends RuntimeException {

    private final List<String> tickers;

    public QuantityOutOfDomainException(List<String> tickers) {
        super("quantity_out_of_domain: " + tickers);
        this.tickers = List.copyOf(tickers);
    }

    public ContractErrorCode code() {
        return ContractErrorCode.quantity_out_of_domain;
    }

    /** Offending tickers in request order, deduplicated. */
    public List<String> tickers() {
        return tickers;
    }
}
