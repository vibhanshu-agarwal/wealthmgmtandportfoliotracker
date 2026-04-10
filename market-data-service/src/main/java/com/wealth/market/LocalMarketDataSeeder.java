package com.wealth.market;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Local bootstrap seeder for baseline market prices.
 *
 * <p>Reads seed data from an externalized JSON fixture file at startup and backfills only
 * missing tickers — repeated runs remain idempotent.
 *
 * <p>If the fixture file is missing or malformed, the error is logged at ERROR level and the
 * method returns normally, allowing the application to continue booting.
 *
 * <p>Restricted to the {@code local} profile — never instantiated in {@code aws} or other
 * production profiles.
 */
@Component
@Profile("local")
class LocalMarketDataSeeder implements ApplicationRunner, ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(LocalMarketDataSeeder.class);

    private final AssetPriceRepository assetPriceRepository;
    private final MarketPriceService marketPriceService;
    private final ObjectMapper objectMapper;
    private final MarketSeedProperties props;
    private ResourceLoader resourceLoader;

    LocalMarketDataSeeder(AssetPriceRepository assetPriceRepository,
                          MarketPriceService marketPriceService,
                          ObjectMapper objectMapper,
                          MarketSeedProperties props) {
        this.assetPriceRepository = assetPriceRepository;
        this.marketPriceService = marketPriceService;
        // Ensure BigDecimal precision is preserved for basePrice values
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.props = props;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            log.info("Market data seeding disabled (market.seed.enabled=false)");
            return;
        }

        MarketSeedFixture fixture;
        try {
            Resource resource = resourceLoader.getResource(props.fixturePath());
            fixture = objectMapper.readValue(resource.getInputStream(), MarketSeedFixture.class);
        } catch (IllegalArgumentException e) {
            log.error("Market data seeder: invalid fixture path '{}' — {}",
                    props.fixturePath(), e.getMessage(), e);
            return;
        } catch (Exception e) {
            log.error("Market data seeder: failed to load or parse fixture '{}' — {}",
                    props.fixturePath(), e.getMessage(), e);
            return;
        }

        Set<String> existingTickers = new HashSet<>(
                assetPriceRepository.findAll().stream().map(AssetPrice::getTicker).toList()
        );

        int seeded = 0;
        for (SeedAsset asset : fixture.assets()) {
            if (existingTickers.contains(asset.ticker())) {
                continue;
            }
            marketPriceService.updatePrice(asset.ticker(), asset.basePrice());
            seeded++;
        }

        if (seeded == 0) {
            log.info("Market data seed skipped: all baseline tickers already present");
        } else {
            log.info("Seeded {} missing market prices into MongoDB", seeded);
        }
    }
}
