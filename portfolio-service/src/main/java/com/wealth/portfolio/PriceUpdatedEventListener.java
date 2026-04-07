package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.messaging.handler.annotation.Header;

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

    @KafkaListener(
            topics = "market-prices",
            groupId = "portfolio-group",
            containerFactory = "priceUpdatedKafkaListenerContainerFactory"
    )
    void on(PriceUpdatedEvent event) {
        log.info("Kafka: Price update received: {} @ {}", event.ticker(), event.newPrice());
        marketPriceProjectionService.upsertLatestPrice(event);
    }

    @KafkaListener(topics = "market-prices.DLT", groupId = "portfolio-group-dlt")
    void onDlt(
            Object failedPayload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.error(
                "DLT message consumed from topic={} partition={} offset={} payload={}",
                topic,
                partition,
                offset,
                failedPayload
        );
    }
}
