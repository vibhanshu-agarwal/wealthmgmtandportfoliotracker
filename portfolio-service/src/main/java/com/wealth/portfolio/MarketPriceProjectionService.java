package com.wealth.portfolio;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.SupportedCatalog;
import com.wealth.market.events.PriceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains the portfolio-service read model of latest market prices.
 *
 * <p>Price, quote currency, and observation timestamp are one tuple: the upsert writes all three
 * or none of them. Observation identity is truncated to milliseconds once and bound to both
 * {@code market_prices} and {@code market_price_history}.
 */
@Service
class MarketPriceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceProjectionService.class);

    private static final String UPSERT_SQL =
            """
            INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (ticker) DO UPDATE
               SET current_price  = EXCLUDED.current_price,
                   quote_currency = EXCLUDED.quote_currency,
                   observed_at    = EXCLUDED.observed_at,
                   updated_at     = EXCLUDED.updated_at
             WHERE market_prices.observed_at IS NULL
                OR EXCLUDED.observed_at > market_prices.observed_at
            """;

    private static final String HISTORY_INSERT_SQL =
            """
            INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (ticker, observed_at) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final SupportedCatalog catalog;
    private final boolean rejectUnsupportedEvents;
    private final PriceProjectionSignals signals;

    MarketPriceProjectionService(
            JdbcTemplate jdbcTemplate,
            SupportedCatalog catalog,
            @Value("${app.catalog.reject-unsupported-events:true}") boolean rejectUnsupportedEvents,
            PriceProjectionSignals signals) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalog = catalog;
        this.rejectUnsupportedEvents = rejectUnsupportedEvents;
        this.signals = signals;
    }

    @Transactional
    public int upsertLatestPrice(PriceUpdatedEvent event) {
        Instant observedAt =
                event.observedAt() == null ? null : event.observedAt().truncatedTo(ChronoUnit.MILLIS);
        String currency = normalizeCurrency(event.ticker(), event.quoteCurrency());
        if (currency == null) {
            return 0;
        }

        if (observedAt == null) {
            signals.undatedEvent(event.ticker());
        }

        Timestamp observedAtTs = observedAt == null ? null : Timestamp.from(observedAt);
        int rows =
                jdbcTemplate.update(
                        UPSERT_SQL,
                        event.ticker(),
                        event.newPrice(),
                        currency,
                        observedAtTs);

        if (rows == 0 && observedAt != null) {
            disambiguateZeroRowResult(event.ticker(), event.newPrice(), currency, observedAt);
        }

        if (observedAt != null) {
            appendHistory(event.ticker(), currency, event.newPrice(), observedAtTs);
        }

        if (rows > 0) {
            log.info("Market price projection updated for ticker {} -> {}", event.ticker(), event.newPrice());
        } else {
            log.debug(
                    "Market price projection skipped for ticker {} at {}", event.ticker(), observedAt);
        }
        return rows;
    }

    /**
     * Returns the currency to persist, or {@code null} when the write is skipped under a closed gate.
     */
    private String normalizeCurrency(String ticker, String incomingCurrency) {
        Optional<CatalogEntry> entry = catalog.find(ticker);
        boolean absent = entry.isEmpty();

        if (incomingCurrency == null) {
            if (entry.isPresent()) {
                return entry.get().quoteCurrency();
            }
            return gatedReject(
                    ticker, RejectedPriceEventException.Reason.CURRENCY_UNRESOLVABLE, null);
        }

        if (absent) {
            return gatedReject(
                    ticker, RejectedPriceEventException.Reason.TICKER_ABSENT, incomingCurrency);
        }

        if (!incomingCurrency.equals(entry.get().quoteCurrency())) {
            return gatedReject(
                    ticker, RejectedPriceEventException.Reason.CURRENCY_MISMATCH, incomingCurrency);
        }
        return incomingCurrency;
    }

    /**
     * Gate on: reject and surface. Gate off: count the would-be rejection. Unresolvable
     * currency cannot be persisted without inventing {@code USD}, so {@code fallbackWhenGateOff}
     * is {@code null} and the write is skipped.
     */
    private String gatedReject(
            String ticker, RejectedPriceEventException.Reason reason, String fallbackWhenGateOff) {
        if (rejectUnsupportedEvents) {
            throw new RejectedPriceEventException(ticker, reason);
        }
        signals.discardedUnsupported(reason.name(), ticker);
        return fallbackWhenGateOff;
    }

    private void disambiguateZeroRowResult(
            String ticker, BigDecimal incomingPrice, String incomingCurrency, Instant incomingObservedAt) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        """
                        SELECT current_price, quote_currency, observed_at
                          FROM market_prices
                         WHERE ticker = ?
                        """,
                        ticker);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> stored = rows.get(0);
        Timestamp storedTs = (Timestamp) stored.get("observed_at");
        if (storedTs == null || !storedTs.toInstant().equals(incomingObservedAt)) {
            return;
        }
        BigDecimal storedPrice = (BigDecimal) stored.get("current_price");
        String storedCurrency = (String) stored.get("quote_currency");
        boolean identical =
                storedPrice.compareTo(incomingPrice) == 0 && incomingCurrency.equals(storedCurrency);
        if (identical) {
            log.debug("Idempotent latest-row skip for ticker {} at {}", ticker, incomingObservedAt);
            return;
        }
        throw new ObservationConflictException(
                ticker,
                "equal observed_at " + incomingObservedAt + " with conflicting payload");
    }

    private void appendHistory(
            String ticker, String currency, BigDecimal price, Timestamp observedAtTs) {
        int historyRows = jdbcTemplate.update(HISTORY_INSERT_SQL, ticker, currency, price, observedAtTs);
        if (historyRows > 0) {
            log.debug("History row appended for ticker {} at {}", ticker, observedAtTs.toInstant());
            return;
        }
        Map<String, Object> stored =
                jdbcTemplate.queryForMap(
                        """
                        SELECT price, quote_currency
                          FROM market_price_history
                         WHERE ticker = ? AND observed_at = ?
                        """,
                        ticker,
                        observedAtTs);
        BigDecimal storedPrice = (BigDecimal) stored.get("price");
        String storedCurrency = (String) stored.get("quote_currency");
        boolean identical = storedPrice.compareTo(price) == 0 && currency.equals(storedCurrency);
        if (identical) {
            log.debug("Idempotent history skip for ticker {} at {}", ticker, observedAtTs.toInstant());
            return;
        }
        throw new ObservationConflictException(
                ticker, "history key conflict at " + observedAtTs.toInstant());
    }
}
