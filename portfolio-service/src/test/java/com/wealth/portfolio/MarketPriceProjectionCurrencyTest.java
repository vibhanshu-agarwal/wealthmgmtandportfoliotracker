package com.wealth.portfolio;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 8.2: currency normalisation happens before any tuple comparison, and rejection
 * is gated by {@code app.catalog.reject-unsupported-events} (default {@code true} since cutover
 * checkpoint 9.8, supported-asset-integrity Task 9; this test constructs the service directly with
 * an explicit flag value per case, independent of that default).
 */
@ExtendWith(MockitoExtension.class)
class MarketPriceProjectionCurrencyTest {

    private static final BigDecimal PRICE = new BigDecimal("10.00");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SupportedCatalog catalog;
    private PriceProjectionSignals signals;

    @BeforeEach
    void setUp() {
        catalog = SupportedCatalog.load();
        signals = new PriceProjectionSignals();
    }

    private MarketPriceProjectionService service(boolean rejectUnsupportedEvents) {
        return new MarketPriceProjectionService(
                jdbcTemplate, catalog, rejectUnsupportedEvents, signals);
    }

    @Test
    void nullCurrency_resolvableFromCatalog_resolvesAndWrites_regardlessOfGate() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        service(false)
                .upsertLatestPrice(new PriceUpdatedEvent("RELIANCE.NS", PRICE, null, null, null, null));

        verify(jdbcTemplate)
                .update(anyString(), eq("RELIANCE.NS"), eq(PRICE), eq("INR"), any());
        assertThat(signals.discardedCount()).isZero();
    }

    @Test
    void nullCurrency_unresolvable_gateOff_skipsWriteAndCounts() {
        service(false)
                .upsertLatestPrice(new PriceUpdatedEvent("NOT_IN_CATALOG", PRICE, null, null, null, null));

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
        assertThat(signals.discardedCount()).isEqualTo(1);
        assertThat(signals.lastDiscardReason()).isEqualTo("CURRENCY_UNRESOLVABLE");
    }

    @Test
    void nullCurrency_unresolvable_gateOn_rejectsAndDoesNotWrite() {
        assertThatThrownBy(
                        () ->
                                service(true)
                                        .upsertLatestPrice(
                                                new PriceUpdatedEvent(
                                                        "NOT_IN_CATALOG", PRICE, null, null, null, null)))
                .isInstanceOf(RejectedPriceEventException.class)
                .extracting(t -> ((RejectedPriceEventException) t).reason())
                .isEqualTo(RejectedPriceEventException.Reason.CURRENCY_UNRESOLVABLE);

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void tickerAbsent_nonNullCurrency_gateOff_writesPreExistingAndCounts() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        service(false)
                .upsertLatestPrice(
                        new PriceUpdatedEvent("NOT_IN_CATALOG", PRICE, "USD", null, null, null));

        verify(jdbcTemplate)
                .update(anyString(), eq("NOT_IN_CATALOG"), eq(PRICE), eq("USD"), any());
        assertThat(signals.discardedCount()).isEqualTo(1);
        assertThat(signals.lastDiscardReason()).isEqualTo("TICKER_ABSENT");
    }

    @Test
    void tickerAbsent_gateOn_rejectsRegardlessOfCurrency() {
        assertThatThrownBy(
                        () ->
                                service(true)
                                        .upsertLatestPrice(
                                                new PriceUpdatedEvent(
                                                        "NOT_IN_CATALOG", PRICE, "USD", null, null, null)))
                .isInstanceOf(RejectedPriceEventException.class)
                .extracting(t -> ((RejectedPriceEventException) t).reason())
                .isEqualTo(RejectedPriceEventException.Reason.TICKER_ABSENT);

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void currencyMismatch_gateOff_writesIncomingAndCounts() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        service(false)
                .upsertLatestPrice(
                        new PriceUpdatedEvent("RELIANCE.NS", PRICE, "USD", null, null, null));

        verify(jdbcTemplate)
                .update(anyString(), eq("RELIANCE.NS"), eq(PRICE), eq("USD"), any());
        assertThat(signals.discardedCount()).isEqualTo(1);
        assertThat(signals.lastDiscardReason()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void currencyMismatch_gateOn_rejectsAndDoesNotWrite() {
        assertThatThrownBy(
                        () ->
                                service(true)
                                        .upsertLatestPrice(
                                                new PriceUpdatedEvent(
                                                        "RELIANCE.NS", PRICE, "USD", null, null, null)))
                .isInstanceOf(RejectedPriceEventException.class)
                .extracting(t -> ((RejectedPriceEventException) t).reason())
                .isEqualTo(RejectedPriceEventException.Reason.CURRENCY_MISMATCH);

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void matchingNonNullCurrency_isNotAltered() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        service(false)
                .upsertLatestPrice(
                        new PriceUpdatedEvent("RELIANCE.NS", PRICE, "INR", null, null, null));

        verify(jdbcTemplate)
                .update(anyString(), eq("RELIANCE.NS"), eq(PRICE), eq("INR"), any());
        assertThat(signals.discardedCount()).isZero();
    }
}
