package com.wealth.insight;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class InsightEventListener {

    private static final Logger log = LoggerFactory.getLogger(InsightEventListener.class);

    @KafkaListener(topics = "market-prices", groupId = "insight-group")
    public void onPriceUpdated(PriceUpdatedEvent event) {
        log.info("Insight: Processing price update for ticker: {} @ {}", event.ticker(), event.newPrice());
        // Potential logic: Update insight cache, trigger re-calculation of risk metrics, etc.
    }
}
