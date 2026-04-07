package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
class PriceUpdatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PriceUpdatedEventListener.class);

    private final MarketPriceProjectionService marketPriceProjectionService;

    PriceUpdatedEventListener(MarketPriceProjectionService marketPriceProjectionService) {
        this.marketPriceProjectionService = marketPriceProjectionService;
    }

    @KafkaListener(topics = "market-prices", groupId = "portfolio-group")
    void on(PriceUpdatedEvent event) {
        log.info("Kafka: Price update received: {} @ {}", event.ticker(), event.newPrice());
        marketPriceProjectionService.upsertLatestPrice(event);
    }
}
