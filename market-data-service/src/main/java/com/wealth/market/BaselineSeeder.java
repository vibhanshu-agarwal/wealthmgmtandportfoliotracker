package com.wealth.market;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Profile-agnostic baseline seeder that ensures all configured baseline tickers
 * exist in Mongo as AssetPrice documents. It does NOT set prices; it only
 * ensures ticker presence so that later flows (scheduled refresh, etc.) can
 * attach real prices.
 */
@Component
@ConditionalOnProperty(prefix = "market-data.baseline-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
class BaselineSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineSeeder.class);

    private final AssetPriceRepository assetPriceRepository;
    private final BaselineTickerProperties baselineTickerProperties;
    private final MeterRegistry meterRegistry;

    BaselineSeeder(AssetPriceRepository assetPriceRepository,
                   BaselineTickerProperties baselineTickerProperties,
                   MeterRegistry meterRegistry) {
        this.assetPriceRepository = assetPriceRepository;
        this.baselineTickerProperties = baselineTickerProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> baselineTickers = baselineTickerProperties.getTickers();
        if (baselineTickers == null || baselineTickers.isEmpty()) {
            log.info("BaselineSeeder: no baseline tickers configured; skipping seeding.");
            return;
        }

        Set<String> existingTickers = new HashSet<>(
                assetPriceRepository.findAll().stream().map(AssetPrice::getTicker).toList()
        );

        int inserted = 0;
        for (String ticker : baselineTickers) {
            if (existingTickers.contains(ticker)) {
                continue;
            }
            // Insert a shell AssetPrice without a price; scheduled job will populate currentPrice.
            assetPriceRepository.save(new AssetPrice(ticker, null));
            inserted++;
        }

        if (inserted == 0) {
            log.info("BaselineSeeder: all baseline tickers already present in MongoDB");
        } else {
            log.info("BaselineSeeder: inserted {} new baseline tickers into MongoDB", inserted);
            Counter.builder("market.data.baseline.inserted")
                    .description("Baseline AssetPrice shell documents inserted at startup")
                    .register(meterRegistry)
                    .increment(inserted);
        }
    }
}

