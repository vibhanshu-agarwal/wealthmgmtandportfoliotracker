package com.wealth.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.wealth.market.events.PriceUpdatedEvent;

@Service
public class InsightEventListener {

    private static final Logger log = LoggerFactory.getLogger(InsightEventListener.class);

    private final MarketDataService marketDataService;

    public InsightEventListener(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @KafkaListener(topics = "market-prices", groupId = "insight-group")
    public void onPriceUpdated(PriceUpdatedEvent event) {
        log.info("Insight: Processing price update for ticker: {} @ {}", event.ticker(), event.newPrice());
        marketDataService.processUpdate(event);
    }
}
