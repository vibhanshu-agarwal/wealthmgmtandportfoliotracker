package com.wealth.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "market.baseline")
public class BaselineTickerProperties {

    /**
     * Provider-formatted baseline tickers (US tech, NSE, crypto, FX).
     * This list is reused by the seeder and scheduled refresh job.
     */
    private List<String> tickers = List.of();

    public List<String> getTickers() {
        return tickers;
    }

    public void setTickers(List<String> tickers) {
        this.tickers = tickers;
    }
}

