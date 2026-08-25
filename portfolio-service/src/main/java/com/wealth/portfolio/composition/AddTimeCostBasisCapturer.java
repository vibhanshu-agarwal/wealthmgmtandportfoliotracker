package com.wealth.portfolio.composition;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Existing add-time rule: look up {@code market_prices}; when a positive current price exists,
 * capture it as {@code ADD_TIME} basis. Otherwise leave the basis tuple null (typed unavailable).
 */
@Component
public class AddTimeCostBasisCapturer {

    private static final Logger log = LoggerFactory.getLogger(AddTimeCostBasisCapturer.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AddTimeCostBasisCapturer(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public DesiredHoldingState captureNew(String ticker, BigDecimal quantity) {
        try {
            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList(
                            "SELECT current_price, quote_currency FROM market_prices WHERE ticker = ?",
                            ticker);
            if (!rows.isEmpty()) {
                BigDecimal price = (BigDecimal) rows.getFirst().get("current_price");
                String quoteCurrency = (String) rows.getFirst().get("quote_currency");
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    Instant asOf = clock.instant();
                    return new DesiredHoldingState(
                            ticker,
                            quantity,
                            price,
                            quoteCurrency != null ? quoteCurrency : "USD",
                            "ADD_TIME",
                            asOf);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Could not capture cost basis for {} — fields remain null: {}",
                    ticker,
                    e.getMessage());
        }
        return new DesiredHoldingState(ticker, quantity, null, null, null, null);
    }
}
