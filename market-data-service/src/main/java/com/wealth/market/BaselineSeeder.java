package com.wealth.market;

import com.wealth.market.seed.SeedTickerRegistry;
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
 *
 * <p>Wave 2: {@code quoteCurrency} is now resolved from {@link SeedTickerRegistry} so that
 * the shell document has accurate currency metadata even before a price is set.
 */
@Component
@ConditionalOnProperty(prefix = "market-data.baseline-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
class BaselineSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineSeeder.class);

    private final AssetPriceRepository assetPriceRepository;
    private final BaselineTickerProperties baselineTickerProperties;
    private final SeedTickerRegistry seedTickerRegistry;
    private final MeterRegistry meterRegistry;

    BaselineSeeder(AssetPriceRepository assetPriceRepository,
                   BaselineTickerProperties baselineTickerProperties,
                   SeedTickerRegistry seedTickerRegistry,
                   MeterRegistry meterRegistry) {
        this.assetPriceRepository = assetPriceRepository;
        this.baselineTickerProperties = baselineTickerProperties;
        this.seedTickerRegistry = seedTickerRegistry;
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
            // Resolve quoteCurrency from the registry if available; null is tolerated.
            String quoteCurrency = seedTickerRegistry.find(ticker)
                    .map(SeedTickerRegistry.SeedTicker::quoteCurrency)
                    .orElse(null);

            // Insert a shell AssetPrice without a price; scheduled job will populate currentPrice.
            AssetPrice shell = new AssetPrice(ticker, null);
            shell.setQuoteCurrency(quoteCurrency);
            assetPriceRepository.save(shell);
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
