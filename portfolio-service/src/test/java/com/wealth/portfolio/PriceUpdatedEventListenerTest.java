package com.wealth.portfolio;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.kafka.MalformedEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PriceUpdatedEventListenerTest {

    @Mock
    private MarketPriceProjectionService projectionService;

    private PriceUpdatedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PriceUpdatedEventListener(projectionService);
    }

    @Test
    void on_nullEvent_throwsMalformedEventException() {
        assertThatThrownBy(() -> listener.on(null))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(null);
    }

    @Test
    void on_nullTicker_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent(null, BigDecimal.TEN);
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_emptyTicker_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent("", BigDecimal.TEN);
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_blankTicker_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent("   ", BigDecimal.TEN);
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_nullNewPrice_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent("AAPL", null);
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_zeroNewPrice_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent("AAPL", BigDecimal.ZERO);
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_negativeNewPrice_throwsMalformedEventException() {
        var event = new PriceUpdatedEvent("AAPL", new BigDecimal("-1.00"));
        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(MalformedEventException.class);
        verify(projectionService, never()).upsertLatestPrice(event);
    }

    @Test
    void on_validEvent_delegatesToProjectionServiceExactlyOnce() {
        var event = new PriceUpdatedEvent("AAPL", new BigDecimal("150.00"));
        listener.on(event);
        verify(projectionService).upsertLatestPrice(event);
    }
}
