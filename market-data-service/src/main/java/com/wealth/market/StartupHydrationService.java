package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * On application startup, republishes PriceUpdatedEvent for all tickers that
 * have a known (non-null) currentPrice in MongoDB. This hydrates downstream
 * caches after restarts without mutating MongoDB state.
 */
@Component
@ConditionalOnProperty(prefix = "market-data.hydration", name = "enabled", havingValue = "true", matchIfMissing = true)
class StartupHydrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupHydrationService.class);
    private static final String TOPIC = "market-prices";

    private final AssetPriceRepository assetPriceRepository;
    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    StartupHydrationService(AssetPriceRepository assetPriceRepository,
                            KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate,
                            MeterRegistry meterRegistry) {
        this.assetPriceRepository = assetPriceRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        MDC.put("marketDataStartupHydrationId", UUID.randomUUID().toString());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            runHydration();
        } finally {
            sample.stop(Timer.builder("market.data.startup.hydration")
                    .description("Time to republish cached prices to Kafka on startup")
                    .register(meterRegistry));
            MDC.remove("marketDataStartupHydrationId");
        }
    }

    private void runHydration() {
        List<AssetPrice> assets;
        try {
            assets = assetPriceRepository.findAll();
        } catch (Exception e) {
            log.warn("StartupHydrationService: failed to load asset prices from MongoDB — skipping hydration. Cause: {}",
                    e.getMessage());
            return;
        }
        if (assets.isEmpty()) {
            log.info("StartupHydrationService: no asset prices found, nothing to hydrate.");
            return;
        }

        int published = 0;
        for (AssetPrice asset : assets) {
            BigDecimal price = asset.getCurrentPrice();
            if (price == null) {
                log.debug("StartupHydrationService: skipping ticker {} with null price", asset.getTicker());
                continue;
            }
            var event = new PriceUpdatedEvent(asset.getTicker(), price);
            kafkaTemplate.send(TOPIC, asset.getTicker(), event);
            published++;
        }

        log.info("StartupHydrationService: published {} PriceUpdatedEvent(s) for cache hydration", published);
        meterRegistry.counter("market.data.startup.hydration.events", "phase", "published").increment(published);
    }
}
