package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class MarketPriceService {

    private final AssetPriceRepository assetPriceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public MarketPriceService(AssetPriceRepository assetPriceRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.assetPriceRepository = assetPriceRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Persists the new price and publishes a {@link PriceUpdatedEvent}.
     *
     * <p>The event is published inside the transaction. Spring Modulith's JDBC Event
     * Publication Registry writes it to {@code event_publication} atomically with the
     * price row — guaranteeing delivery even if the downstream listener fails.
     */
    @Transactional
    public void updatePrice(String ticker, BigDecimal newPrice) {
        var price = assetPriceRepository.findById(ticker)
                .orElseGet(() -> new AssetPrice(ticker, newPrice));

        price.setCurrentPrice(newPrice);
        assetPriceRepository.save(price);

        eventPublisher.publishEvent(new PriceUpdatedEvent(ticker, newPrice));
    }
}
