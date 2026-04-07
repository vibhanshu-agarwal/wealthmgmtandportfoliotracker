package com.wealth.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local bootstrap seeder for baseline market prices.
 *
 * <p>This runs at startup and backfills missing symbols only, so repeated runs remain idempotent.
 */
@Component
class LocalMarketDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalMarketDataSeeder.class);

    private final AssetPriceRepository assetPriceRepository;
    private final MarketPriceService marketPriceService;
    private final boolean enabled;

    LocalMarketDataSeeder(AssetPriceRepository assetPriceRepository,
                          MarketPriceService marketPriceService,
                          @Value("${market.seed.enabled:true}") boolean enabled) {
        this.assetPriceRepository = assetPriceRepository;
        this.marketPriceService = marketPriceService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Market data seeding disabled (market.seed.enabled=false)");
            return;
        }

        Map<String, BigDecimal> seedPrices = Map.of(
                "AAPL", new BigDecimal("212.5000"),
                "TSLA", new BigDecimal("276.0000"),
                "BTC", new BigDecimal("70775.0000"),
                "MSFT", new BigDecimal("425.3000"),
                "NVDA", new BigDecimal("938.6000"),
                "ETH", new BigDecimal("3540.5000")
        );
        // TODO: Replace static seed map with configurable fixture profiles or external feed snapshots.

        Set<String> existingTickers = new HashSet<>(
                assetPriceRepository.findAll().stream().map(AssetPrice::getTicker).toList()
        );

        int seeded = 0;
        for (Map.Entry<String, BigDecimal> entry : seedPrices.entrySet()) {
            if (existingTickers.contains(entry.getKey())) {
                continue;
            }
            marketPriceService.updatePrice(entry.getKey(), entry.getValue());
            seeded++;
        }

        if (seeded == 0) {
            log.info("Market data seed skipped: all baseline tickers already present");
            return;
        }

        log.info("Seeded {} missing market prices into MongoDB", seeded);
    }
}
