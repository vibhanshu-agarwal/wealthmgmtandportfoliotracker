package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Consumes {@link PriceUpdatedEvent} published by the Market module.
 *
 * <p>{@code @ApplicationModuleListener} is a Spring Modulith composed annotation that combines:
 * <ul>
 *   <li>{@code @TransactionalEventListener} — fires after the publishing transaction commits,
 *       so the price row is guaranteed to be visible before this runs</li>
 *   <li>{@code @Async} — executes in a separate thread, decoupling the listener's latency
 *       from the publisher's transaction</li>
 *   <li>{@code @Transactional} — wraps the handler in its own transaction so any writes
 *       here are atomic</li>
 * </ul>
 *
 * <p>Delivery guarantee: if this listener fails, the event remains in the
 * {@code event_publication} table with status UNPROCESSED and will be retried on
 * the next application restart (Outbox Pattern).
 */
@Service
class PriceUpdatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PriceUpdatedEventListener.class);

    private final PortfolioRepository portfolioRepository;
    private final AssetHoldingRepository assetHoldingRepository;

    PriceUpdatedEventListener(PortfolioRepository portfolioRepository,
                              AssetHoldingRepository assetHoldingRepository) {
        this.portfolioRepository = portfolioRepository;
        this.assetHoldingRepository = assetHoldingRepository;
    }

    @ApplicationModuleListener
    void on(PriceUpdatedEvent event) {
        log.info("Price update received: {} @ {}", event.ticker(), event.newPrice());

        // Find all holdings for this ticker across every portfolio and flag them
        // for re-valuation. Full valuation logic is implemented in Step 5.
        var affectedHoldings = assetHoldingRepository.findByAssetTicker(event.ticker());

        if (affectedHoldings.isEmpty()) {
            log.debug("No holdings found for ticker {}. No action taken.", event.ticker());
            return;
        }

        log.info("{} holding(s) affected by price change for {}", affectedHoldings.size(), event.ticker());
    }
}
