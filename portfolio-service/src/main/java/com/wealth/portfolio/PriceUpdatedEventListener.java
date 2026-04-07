package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer boundary for market price updates.
 *
 * <p>The listener stays thin and delegates business-side projection writes to
 * {@link MarketPriceProjectionService}.
 */
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
        // TODO: Route malformed/poison events to a dead-letter strategy after retry exhaustion.
        marketPriceProjectionService.upsertLatestPrice(event);
    }
}
