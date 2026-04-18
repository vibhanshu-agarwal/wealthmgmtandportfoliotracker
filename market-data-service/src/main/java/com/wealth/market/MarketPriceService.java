package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Application service that owns the market price write path.
 *
 * <p>It performs two actions in sequence:
 * 1) persists the latest ticker price in MongoDB
 * 2) publishes a {@link PriceUpdatedEvent} to Kafka for downstream consumers
 */
@Service
public class MarketPriceService {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceService.class);
    private static final String TOPIC = "market-prices";

    private final AssetPriceRepository assetPriceRepository;

    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    public MarketPriceService(AssetPriceRepository assetPriceRepository,
                              KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate) {
        this.assetPriceRepository = assetPriceRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Persists the new price to MongoDB and publishes a {@link PriceUpdatedEvent} to Kafka.
     */
    public void updatePrice(String ticker, BigDecimal newPrice) {
        log.info("Updating price for ticker: {} to {}", ticker, newPrice);
        
        var price = assetPriceRepository.findById(ticker)
                .orElseGet(() -> new AssetPrice(ticker, newPrice));

        price.setCurrentPrice(newPrice);
        assetPriceRepository.save(price);

        // Publish with ticker as key to preserve partition-level ordering per asset symbol.
        var event = new PriceUpdatedEvent(ticker, newPrice);
        kafkaTemplate.send(TOPIC, ticker, event);
        
        log.info("Published PriceUpdatedEvent to Kafka for ticker: {}", ticker);
    }
}
