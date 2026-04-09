package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.kafka.MalformedEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.messaging.handler.annotation.Header;

import java.math.BigDecimal;

/**
 * Kafka consumer boundary for market price updates.
 *
 * <p>The listener validates the incoming event before delegating to
 * {@link MarketPriceProjectionService}. Invalid events throw {@link MalformedEventException},
 * which is registered as non-retryable and routes directly to {@code market-prices.DLT}.
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
        if (event == null) {
            throw new MalformedEventException("PriceUpdatedEvent must not be null");
        }
        if (event.ticker() == null || event.ticker().isBlank()) {
            throw new MalformedEventException(
                    "PriceUpdatedEvent.ticker must not be null or blank, got: " + event.ticker());
        }
        if (event.newPrice() == null) {
            throw new MalformedEventException(
                    "PriceUpdatedEvent.newPrice must not be null for ticker: " + event.ticker());
        }
        if (event.newPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new MalformedEventException(
                    "PriceUpdatedEvent.newPrice must be positive, got: " + event.newPrice()
                    + " for ticker: " + event.ticker());
        }

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
                "DLT: Failed record received — topic={} partition={} offset={} payload={}",
                topic,
                partition,
                offset,
                failedPayload
        );
    }
}
