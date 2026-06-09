package com.wealth.portfolio.fx;

import com.wealth.portfolio.FxRateProvider;
import com.wealth.portfolio.FxRateUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/**
 * AWS- and Azure-profile FX rate provider that fetches daily rates from the free
 * <a href="https://open.er-api.com">Open Exchange Rates API</a> (no API key required).
 *
 * <p>Active under both the {@code aws} and {@code azure} Spring profiles. Both clouds
 * use the same public Open Exchange Rates endpoint; the URL is read from
 * {@code fx.aws.rates-url} (AWS) or {@code fx.azure.rates-url} (Azure) in the
 * respective profile YAML overlay.
 *
 * <h3>Bulk caching strategy</h3>
 * The entire rate map is fetched in a single HTTP call via {@link #fetchRateMap()} and cached
 * under the key {@code "all"} in the {@code fx-rates} cache. All {@link #getRate} calls derive
 * the cross-rate locally from the cached map — no per-pair HTTP calls are ever made.
 *
 * <h3>Fault tolerance (Wave 3 / Task 6 update)</h3>
 * If the HTTP call fails, {@link #fetchRateMap()} logs the error and returns a fallback map
 * containing only {@code USD → 1.0}. In {@link #getRate}, if either currency is absent from the
 * map (including the fallback case for non-USD pairs), a {@link com.wealth.portfolio.FxRateUnavailableException}
 * is thrown — consistent with {@link StaticFxRateProvider}. This surfaces partial-availability in
 * aggregates rather than silently converting 1:1 for non-USD holdings.
 */
@Service
@Profile({"aws", "azure"})
public class EcbFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(EcbFxRateProvider.class);

    private final RestClient restClient;

    /** Fallback map returned when the external API is unreachable. Intentionally USD-only. */
    private static final Map<String, BigDecimal> FALLBACK_RATES = Map.of("USD", BigDecimal.ONE);

    @Autowired
    @Lazy
    private EcbFxRateProvider self;

    public EcbFxRateProvider(RestClient.Builder builder, FxProperties props) {
        // Resolve the rates URL from whichever profile overlay is active.
        // Azure profile takes precedence if both are somehow present; falls back to the
        // well-known open.er-api.com endpoint if neither overlay is configured.
        String ratesUrl;
        if (props.azure() != null && props.azure().ratesUrl() != null) {
            ratesUrl = props.azure().ratesUrl();
        } else if (props.aws() != null && props.aws().ratesUrl() != null) {
            ratesUrl = props.aws().ratesUrl();
        } else {
            ratesUrl = "https://open.er-api.com/v6/latest/USD";
        }
        this.restClient = builder.baseUrl(ratesUrl).build();
    }

    /**
     * Fetches the entire rate map in a single HTTP GET and caches it under key {@code "all"}.
     *
     * <p>On any exception the error is logged and a fallback map of {@code {USD: 1.0}} is
     * returned so that callers degrade gracefully to 1:1 conversion.
     *
     * @return map of ISO 4217 code → units of that currency per 1 USD
     */
    @Cacheable(value = "fx-rates", key = "'all'")
    public Map<String, BigDecimal> fetchRateMap() {
        try {
            ExchangeRateResponse response = restClient.get()
                    .retrieve()
                    .body(ExchangeRateResponse.class);

            if (response == null || response.rates() == null || response.rates().isEmpty()) {
                log.error("FX rate API returned empty or null response — using 1:1 fallback");
                return FALLBACK_RATES;
            }

            log.info("FX rates refreshed — {} currencies loaded", response.rates().size());
            return response.rates();

        } catch (Exception ex) {
            log.error("Failed to fetch FX rates from external API — using 1:1 fallback. Cause: {}",
                    ex.getMessage(), ex);
            return FALLBACK_RATES;
        }
    }

    /**
     * Evicts the cached rate map daily so rates stay fresh without a service restart.
     * The next {@link #getRate} call after eviction will trigger a fresh HTTP fetch.
     *
     * <p>Reads {@code fx.refresh-cron} (a profile-neutral key set in both
     * {@code application-aws.yml} and {@code application-azure.yml}), falling back
     * to daily at 06:00 UTC.
     */
    @Scheduled(cron = "${fx.refresh-cron:0 0 6 * * *}")
    @CacheEvict(value = "fx-rates", allEntries = true)
    public void evictDailyRates() {
        log.info("FX rate cache evicted — rates will refresh on next request");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Retrieves the full rate map from cache (or fetches it on first call), then derives
     * the cross-rate locally: {@code ratesFromUsd[to] / ratesFromUsd[from]}.
     *
     * <p>If either currency is absent from the map (e.g. during API fallback for non-USD pairs),
     * a {@link FxRateUnavailableException} is thrown so callers can surface partial-availability
     * rather than silently converting 1:1. This is consistent with {@link StaticFxRateProvider}.
     *
     * @throws com.wealth.portfolio.FxRateUnavailableException when the rate cannot be resolved for a non-equal pair
     */
    @Override
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        Map<String, BigDecimal> rates = self.fetchRateMap();

        BigDecimal rateFrom = rates.get(fromCurrency);
        BigDecimal rateTo   = rates.get(toCurrency);

        // Task 6.1: stop returning BigDecimal.ONE / USD-only 1:1 fallback for non-equal currencies.
        // Surface FxRateUnavailableException so portfolio aggregates expose partial-availability
        // rather than silently undercounting or fabricating a 1:1 rate.
        if (rateFrom == null || rateFrom.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("FX rate map missing or zero entry for '{}' → '{}'  — rate unavailable",
                    fromCurrency, toCurrency);
            throw new com.wealth.portfolio.FxRateUnavailableException(fromCurrency, toCurrency, null);
        }
        if (rateTo == null) {
            log.warn("FX rate map missing entry for '{}' → '{}' — rate unavailable",
                    fromCurrency, toCurrency);
            throw new com.wealth.portfolio.FxRateUnavailableException(fromCurrency, toCurrency, null);
        }

        return rateTo.divide(rateFrom, MathContext.DECIMAL64);
    }

    /**
     * Internal DTO for deserialising the open.er-api.com JSON response.
     * Only the {@code rates} field is needed.
     */
    record ExchangeRateResponse(Map<String, BigDecimal> rates) {
    }
}
