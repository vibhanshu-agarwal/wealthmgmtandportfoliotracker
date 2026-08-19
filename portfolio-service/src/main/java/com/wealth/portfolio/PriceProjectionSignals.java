package com.wealth.portfolio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observable counters for projection paths that must not be silent (Requirements 9.9, 9.22).
 */
@Component
class PriceProjectionSignals {

    private static final Logger log = LoggerFactory.getLogger(PriceProjectionSignals.class);

    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong undated = new AtomicLong();
    private volatile String lastDiscardReason;

    void discardedUnsupported(String reason, String ticker) {
        discarded.incrementAndGet();
        lastDiscardReason = reason;
        log.warn("would_reject_unsupported_event reason={} ticker={} gate=false", reason, ticker);
    }

    void undatedEvent(String ticker) {
        undated.incrementAndGet();
        log.warn("undated_price_event ticker={}", ticker);
    }

    long discardedCount() {
        return discarded.get();
    }

    long undatedCount() {
        return undated.get();
    }

    String lastDiscardReason() {
        return lastDiscardReason;
    }
}
