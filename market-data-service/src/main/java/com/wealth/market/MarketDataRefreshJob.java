package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "market-data.refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
class MarketDataRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshJob.class);
    private static final String TOPIC = "market-prices";

    private final AssetPriceRepository assetPriceRepository;
    private final ExternalMarketDataClient externalMarketDataClient;
    private final BaselineTickerProperties baselineTickerProperties;
    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    MarketDataRefreshJob(AssetPriceRepository assetPriceRepository,
                         ExternalMarketDataClient externalMarketDataClient,
                         BaselineTickerProperties baselineTickerProperties,
                         KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate,
                         MeterRegistry meterRegistry) {
        this.assetPriceRepository = assetPriceRepository;
        this.externalMarketDataClient = externalMarketDataClient;
        this.baselineTickerProperties = baselineTickerProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${market-data.refresh.cron:0 0 */1 * * *}")
    void refreshAllTrackedTickers() {
        MDC.put("marketDataRefreshJobId", UUID.randomUUID().toString());
        Timer.Sample wallClock = Timer.start(meterRegistry);
        Instant started = Instant.now();
        try {
            List<String> tickers = resolveTrackedTickers();
            if (tickers.isEmpty()) {
                log.info("MarketDataRefreshJob: no tracked tickers (baseline + Mongo); skipping refresh.");
                meterRegistry.counter("market.data.refresh.outcome", "result", "empty").increment();
                return;
            }

            log.info("MarketDataRefreshJob: starting refresh for {} ticker(s)", tickers.size());

            Map<String, BigDecimal> latestPrices;
            try {
                latestPrices = externalMarketDataClient.getLatestPrices(tickers);
            } catch (Exception e) {
                log.error("MarketDataRefreshJob: Yahoo Finance API failed, falling back to cached database prices. " +
                        "Continuing to serve last-known prices for all tickers. cause={}", e.toString());
                meterRegistry.counter("market.data.refresh.outcome", "result", "provider_error").increment();
                return;
            }

            int updated = 0;
            int skipped = 0;
            int failed = 0;

            for (String ticker : tickers) {
                BigDecimal newPrice = latestPrices.get(ticker);
                if (newPrice == null) {
                    log.warn("MarketDataRefreshJob: skipped ticker {} (no price from provider)", ticker);
                    skipped++;
                    continue;
                }

                try {
                    AssetPrice assetPrice = assetPriceRepository.findById(ticker)
                            .orElseGet(() -> new AssetPrice(ticker, null));

                    assetPrice.setCurrentPrice(newPrice);
                    assetPriceRepository.save(assetPrice);

                    PriceUpdatedEvent event = new PriceUpdatedEvent(ticker, newPrice);
                    kafkaTemplate.send(TOPIC, ticker, event);
                    log.info("MarketDataRefreshJob: updated ticker {}", ticker);
                    updated++;
                } catch (Exception e) {
                    log.error("MarketDataRefreshJob: failed ticker {} cause={}", ticker, e.toString());
                    failed++;
                }
            }

            Duration elapsed = Duration.between(started, Instant.now());
            log.info("MarketDataRefreshJob: completed refresh in {} ms; updated={}, skipped={}, failed={}",
                    elapsed.toMillis(), updated, skipped, failed);

            meterRegistry.counter("market.data.refresh.tickers", "outcome", "updated").increment(updated);
            meterRegistry.counter("market.data.refresh.tickers", "outcome", "skipped").increment(skipped);
            meterRegistry.counter("market.data.refresh.tickers", "outcome", "failed").increment(failed);
            meterRegistry.counter("market.data.refresh.outcome", "result", "completed").increment();
        } finally {
            wallClock.stop(Timer.builder("market.data.refresh.job")
                    .description("Wall-clock duration of a scheduled market-data refresh run")
                    .register(meterRegistry));
            MDC.remove("marketDataRefreshJobId");
        }
    }

    /**
     * Tracked tickers = configured baseline ∪ any ticker already stored in Mongo (e.g. user-added).
     */
    List<String> resolveTrackedTickers() {
        Set<String> symbols = new LinkedHashSet<>();
        List<String> baseline = baselineTickerProperties.getTickers();
        if (baseline != null) {
            for (String t : baseline) {
                if (t != null && !t.isBlank()) {
                    symbols.add(t.trim());
                }
            }
        }
        for (AssetPrice ap : assetPriceRepository.findAll()) {
            if (ap.getTicker() != null && !ap.getTicker().isBlank()) {
                symbols.add(ap.getTicker().trim());
            }
        }
        return new ArrayList<>(symbols);
    }
}
