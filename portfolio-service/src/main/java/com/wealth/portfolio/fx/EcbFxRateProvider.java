package com.wealth.portfolio.fx;

import com.wealth.portfolio.FxRateProvider;
import com.wealth.portfolio.FxRateUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/**
 * AWS-profile FX rate provider that fetches daily rates from the free
 * <a href="https://open.er-api.com">Open Exchange Rates API</a> (no API key required).
 *
 * <h3>Bulk caching strategy</h3>
 * The entire rate map is fetched in a single HTTP call via {@link #fetchRateMap()} and cached
 * under the key {@code "all"} in the {@code fx-rates} cache. All {@link #getRate} calls derive
 * the cross-rate locally from the cached map — no per-pair HTTP calls are ever made.
 *
 * <h3>Fault tolerance</h3>
 * If the HTTP call fails for any reason, {@link #fetchRateMap()} logs the error and returns a
 * fallback map containing only {@code USD → 1.0}. In {@link #getRate}, if either currency is
 * absent from the map (including the fallback case), a WARN is logged and {@link BigDecimal#ONE}
 * is returned — preventing NPEs and ensuring the portfolio summary endpoint never throws a 500.
 */
@Service
@Profile("aws")
public class EcbFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(EcbFxRateProvider.class);

    /** Fallback map returned when the external API is unreachable. */
    private static final Map<String, BigDecimal> FALLBACK_RATES = Map.of("USD", BigDecimal.ONE);

    private final RestClient restClient;

    public EcbFxRateProvider(RestClient.Builder builder, FxProperties props) {
        String ratesUrl = props.aws() != null && props.aws().ratesUrl() != null
                ? props.aws().ratesUrl()
                : "https://open.er-api.com/v6/latest/USD";
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
     */
    @Scheduled(cron = "${fx.aws.refresh-cron:0 0 6 * * *}")
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
     * a WARN is logged and {@link BigDecimal#ONE} is returned to prevent NPEs and 500 errors.
     *
     * @throws FxRateUnavailableException never — this adapter always returns a value
     */
    @Override
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        Map<String, BigDecimal> rates = fetchRateMap();

        BigDecimal rateFrom = rates.get(fromCurrency);
        BigDecimal rateTo   = rates.get(toCurrency);

        // NPE guard: if either key is missing (e.g. fallback map only has USD),
        // log a warning and return 1:1 so the portfolio summary degrades gracefully.
        if (rateFrom == null || rateFrom.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("FX rate map missing entry for '{}' — falling back to 1:1 conversion", fromCurrency);
            return BigDecimal.ONE;
        }
        if (rateTo == null) {
            log.warn("FX rate map missing entry for '{}' — falling back to 1:1 conversion", toCurrency);
            return BigDecimal.ONE;
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
