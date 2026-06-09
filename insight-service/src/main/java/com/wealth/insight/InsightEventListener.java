package com.wealth.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.wealth.market.events.PriceUpdatedEvent;

/**
 * Kafka consumer for market price updates.
 *
 * <p>Wave 3 / Task 8.1: forwards the full enriched event to {@link MarketDataService},
 * which keys observations by {@code (ticker, observedAt)} identity. Old-shape events
 * (missing {@code observedAt}) are still processed — only the latest-price key is updated
 * and no observation is added to the trend window (no receive-time fabrication).
 */
@Service
public class InsightEventListener {

    private static final Logger log = LoggerFactory.getLogger(InsightEventListener.class);

    private final MarketDataService marketDataService;

    public InsightEventListener(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @KafkaListener(topics = "market-prices", groupId = "insight-group", containerFactory = "kafkaListenerContainerFactory")
    public void onPriceUpdated(PriceUpdatedEvent event) {
        log.info("Insight: Processing price update for ticker: {} @ {} (observedAt={})",
                event.ticker(), event.newPrice(), event.observedAt());
        marketDataService.processUpdate(event);
    }
}
