package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PriceUpdatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PriceUpdatedEventListener.class);

    private final PortfolioRepository portfolioRepository;
    private final AssetHoldingRepository assetHoldingRepository;

    PriceUpdatedEventListener(PortfolioRepository portfolioRepository,
                              AssetHoldingRepository assetHoldingRepository) {
        this.portfolioRepository = portfolioRepository;
        this.assetHoldingRepository = assetHoldingRepository;
    }

    @KafkaListener(topics = "market-prices", groupId = "portfolio-group")
    @Transactional
    void on(PriceUpdatedEvent event) {
        log.info("Kafka: Price update received: {} @ {}", event.ticker(), event.newPrice());

        // Find all holdings for this ticker across every portfolio and flag them
        // for re-valuation.
        var affectedHoldings = assetHoldingRepository.findByAssetTicker(event.ticker());

        if (affectedHoldings.isEmpty()) {
            log.debug("No holdings found for ticker {}. No action taken.", event.ticker());
            return;
        }

        log.info("{} holding(s) affected by price change for {}", affectedHoldings.size(), event.ticker());
    }
}
