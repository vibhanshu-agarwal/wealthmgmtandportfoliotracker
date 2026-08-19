package com.wealth.market;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.SupportedCatalog;
import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class MarketDataRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshService.class);
    private static final String TOPIC = "market-prices";

    private final AssetPriceRepository assetPriceRepository;
    private final ExternalMarketDataClient externalMarketDataClient;
    private final SupportedCatalog supportedCatalog;
    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    MarketDataRefreshService(AssetPriceRepository assetPriceRepository,
                               ExternalMarketDataClient externalMarketDataClient,
                               SupportedCatalog supportedCatalog,
                               KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate,
                               MeterRegistry meterRegistry) {
        this.assetPriceRepository = assetPriceRepository;
        this.externalMarketDataClient = externalMarketDataClient;
        this.supportedCatalog = supportedCatalog;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void refresh() {
        MDC.put("marketDataRefreshJobId", UUID.randomUUID().toString());
        Timer.Sample wallClock = Timer.start(meterRegistry);
        Instant started = Instant.now();
        List<CompletableFuture<SendResult<String, PriceUpdatedEvent>>> sendFutures = new ArrayList<>();
        try {
            List<String> tickers = resolveTrackedTickers();
            if (tickers.isEmpty()) {
                log.info("MarketDataRefreshJob: no tracked tickers (active catalog); skipping refresh.");
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

                    BigDecimal previousReferencePrice = assetPrice.getCurrentPrice();
                    Instant previousReferenceAt = assetPrice.getUpdatedAt();

                    Instant observedAt = Instant.now();
                    assetPrice.recordNewObservation(newPrice, observedAt);
                    assetPriceRepository.save(assetPrice);

                    PriceUpdatedEvent event = new PriceUpdatedEvent(
                            ticker, newPrice,
                            assetPrice.getQuoteCurrency(),
                            observedAt,
                            previousReferencePrice,
                            previousReferenceAt);
                    boolean published = false;
                    try {
                        sendFutures.add(kafkaTemplate.send(TOPIC, ticker, event));
                        published = true;
                    } catch (Exception e) {
                        sendFutures.add(CompletableFuture.failedFuture(e));
                        log.error("MarketDataRefreshJob: publish failed for ticker {} cause={}", ticker, e.toString());
                    }
                    if (published) {
                        log.info("MarketDataRefreshJob: updated ticker {}", ticker);
                        updated++;
                    }
                } catch (Exception e) {
                    log.error("MarketDataRefreshJob: failed ticker {} cause={}", ticker, e.toString());
                    failed++;
                }
            }

            if (!sendFutures.isEmpty()) {
                kafkaTemplate.flush();
                CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0])).join();
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
     * Tracked tickers = active catalog assets.
     */
    List<String> resolveTrackedTickers() {
        // Intentionally does not consult Mongo: active() defines what the product supports.
        return supportedCatalog.active().stream()
                .map(CatalogEntry::ticker)
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList();
    }
}
