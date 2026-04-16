package com.wealth.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external-market-data")
public class ExternalMarketDataProperties {

    private String provider = "yahoo";
    /** Yahoo Finance query host; override per environment if needed. */
    private String baseUrl = "https://query1.finance.yahoo.com";
    private int timeoutMs = 5000;
    private int maxRetries = 3;
    private int backoffMs = 500;
    /** Max symbols per HTTP request to the quote endpoint. */
    private int batchSize = 50;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(int backoffMs) {
        this.backoffMs = backoffMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}

