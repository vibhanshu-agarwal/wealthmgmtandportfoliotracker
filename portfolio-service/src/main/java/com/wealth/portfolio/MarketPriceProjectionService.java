package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the portfolio-service read model of latest market prices.
 *
 * <p>Writes are intentionally idempotent so duplicate Kafka delivery does not corrupt the valuation state.
 */
@Service
class MarketPriceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceProjectionService.class);

    private final JdbcTemplate jdbcTemplate;

    MarketPriceProjectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    @Transactional
    public void upsertLatestPrice(PriceUpdatedEvent event) {
        // "IS DISTINCT FROM" handles both inequality and null-safe comparison for idempotent updates.
        var rowsChanged = jdbcTemplate.update(
                """
                INSERT INTO market_prices (ticker, current_price, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (ticker) DO UPDATE
                SET current_price = EXCLUDED.current_price,
                    updated_at = EXCLUDED.updated_at
                WHERE market_prices.current_price IS DISTINCT FROM EXCLUDED.current_price
                """,
                event.ticker(),
                event.newPrice()
        );

        if (rowsChanged == 0) {
            log.debug("Idempotent skip for ticker {} at price {}", event.ticker(), event.newPrice());
            return;
        }

        log.info("Market price projection updated for ticker {} -> {}", event.ticker(), event.newPrice());
    }
}
