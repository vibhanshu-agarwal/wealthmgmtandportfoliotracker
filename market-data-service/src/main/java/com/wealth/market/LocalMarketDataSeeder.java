package com.wealth.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

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

        if (assetPriceRepository.count() > 0) {
            log.info("Market data seed skipped: collection already contains data");
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

        seedPrices.forEach(marketPriceService::updatePrice);
        log.info("Seeded {} market prices into MongoDB", seedPrices.size());
    }
}
