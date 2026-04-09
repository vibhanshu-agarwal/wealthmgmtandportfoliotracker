package com.wealth.portfolio.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Typed configuration binding for all FX-related settings.
 * Bound from the {@code fx.*} prefix in application-local.yml / application-aws.yml.
 */
@ConfigurationProperties(prefix = "fx")
public record FxProperties(
        String baseCurrency,
        LocalProperties local,
        AwsProperties aws
) {

    /** Returns the base currency, defaulting to "USD" if not configured. */
    public String baseCurrency() {
        return baseCurrency != null ? baseCurrency : "USD";
    }

    /**
     * Local-profile FX configuration.
     *
     * @param staticRates     map of ISO 4217 code → units of that currency per 1 USD
     * @param jitterIntervalMs interval in ms between optional rate jitter updates
     */
    public record LocalProperties(
            Map<String, BigDecimal> staticRates,
            long jitterIntervalMs
    ) {
    }

    /**
     * AWS-profile FX configuration.
     *
     * @param ratesUrl    URL of the free public rates endpoint (e.g. open.er-api.com)
     * @param refreshCron cron expression for daily cache eviction
     */
    public record AwsProperties(
            String ratesUrl,
            String refreshCron
    ) {
    }
}
