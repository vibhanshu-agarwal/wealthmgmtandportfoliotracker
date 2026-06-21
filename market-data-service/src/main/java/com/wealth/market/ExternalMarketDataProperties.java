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

    /**
     * Browser-like User-Agent sent on every request. Yahoo's quote/crumb endpoints reject
     * the default Java/Netty UA, so a realistic value is required to avoid 401/429.
     */
    private String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * URL hit once to obtain the Yahoo session cookie (A1/A3) needed before requesting a crumb.
     * fc.yahoo.com typically returns 404 but still emits the Set-Cookie header, which is all we need.
     */
    private String cookieUrl = "https://fc.yahoo.com/";

    /**
     * Path (relative to {@link #baseUrl}) that returns the crumb token for the current cookie.
     * The crumb must be appended to quote requests as the {@code crumb} query param.
     */
    private String crumbPath = "/v1/test/getcrumb";

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getCookieUrl() {
        return cookieUrl;
    }

    public void setCookieUrl(String cookieUrl) {
        this.cookieUrl = cookieUrl;
    }

    public String getCrumbPath() {
        return crumbPath;
    }

    public void setCrumbPath(String crumbPath) {
        this.crumbPath = crumbPath;
    }

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

