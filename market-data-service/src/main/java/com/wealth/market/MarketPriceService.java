package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Application service that owns the market price write path.
 *
 * <p>It performs two actions in sequence:
 * 1) persists the latest ticker price in MongoDB (rolling the prior price to the reference
 *    fields via {@link AssetPrice#recordNewObservation})
 * 2) publishes an enriched {@link PriceUpdatedEvent} to Kafka for downstream consumers
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
     * Persists the new price to MongoDB (rolling prior price to reference) and publishes an
     * enriched {@link PriceUpdatedEvent} to Kafka.
     *
     * @param ticker        asset ticker symbol
     * @param newPrice      new price in {@code quoteCurrency}
     * @param quoteCurrency ISO currency code for the price; null is tolerated for legacy callers
     */
    public void updatePrice(String ticker, BigDecimal newPrice, String quoteCurrency) {
        log.info("Updating price for ticker: {} to {} ({})", ticker, newPrice, quoteCurrency);

        Instant observedAt = Instant.now();
        AssetPrice price = assetPriceRepository.findById(ticker)
                .orElseGet(() -> {
                    AssetPrice ap = new AssetPrice(ticker, null);
                    ap.setQuoteCurrency(quoteCurrency);
                    return ap;
                });

        // Capture reference before overwriting.
        BigDecimal prevRefPrice = price.getCurrentPrice();
        Instant prevRefAt = price.getUpdatedAt();

        // Roll prior → reference; set new observation.
        price.recordNewObservation(newPrice, observedAt);
        if (quoteCurrency != null) {
            price.setQuoteCurrency(quoteCurrency);
        }
        assetPriceRepository.save(price);

        // Only attach reference if one existed before this update.
        BigDecimal referencePrice = prevRefPrice;
        Instant referenceAt = prevRefAt;

        PriceUpdatedEvent event = new PriceUpdatedEvent(
                ticker, newPrice, price.getQuoteCurrency(), observedAt, referencePrice, referenceAt);
        kafkaTemplate.send(TOPIC, ticker, event);

        log.info("Published enriched PriceUpdatedEvent to Kafka for ticker: {}", ticker);
    }

    /**
     * Legacy overload retained for call sites that do not supply a currency.
     * Delegates with a null currency so the enrichment fields are best-effort.
     */
    public void updatePrice(String ticker, BigDecimal newPrice) {
        updatePrice(ticker, newPrice, null);
    }
}
