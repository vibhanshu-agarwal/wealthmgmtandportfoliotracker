package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositionTuplePreparerTest {

    @Mock AddTimeCostBasisCapturer costBasisCapturer;
    @InjectMocks CompositionTuplePreparer preparer;

    @Test
    void retainedTickerKeepsExistingCostBasisWhenQuantityChanges() {
        Instant asOf = Instant.parse("2026-01-01T00:00:00Z");
        HoldingSnapshot locked =
                new HoldingSnapshot(
                        "AAPL",
                        new BigDecimal("1.00000000"),
                        new BigDecimal("100.0000"),
                        "USD",
                        "ADD_TIME",
                        asOf);

        List<DesiredHoldingState> desired =
                preparer.materialise(
                        List.of(new RawIntent("AAPL", new BigDecimal("2.50000000"))),
                        List.of(locked));

        assertThat(desired).hasSize(1);
        DesiredHoldingState row = desired.getFirst();
        assertThat(row.quantity()).isEqualByComparingTo("2.50000000");
        assertThat(row.avgCostBasis()).isEqualByComparingTo("100.0000");
        assertThat(row.costBasisCurrency()).isEqualTo("USD");
        assertThat(row.costBasisSource()).isEqualTo("ADD_TIME");
        assertThat(row.costBasisAsOf()).isEqualTo(asOf);
        verifyNoInteractions(costBasisCapturer);
    }

    @Test
    void newTickerCapturesAddTimeBasis() {
        DesiredHoldingState captured =
                new DesiredHoldingState(
                        "MSFT",
                        new BigDecimal("3"),
                        new BigDecimal("410.0000"),
                        "USD",
                        "ADD_TIME",
                        Instant.parse("2026-02-01T00:00:00Z"));
        when(costBasisCapturer.captureNew(eq("MSFT"), eq(new BigDecimal("3")))).thenReturn(captured);

        List<DesiredHoldingState> desired =
                preparer.materialise(
                        List.of(new RawIntent("MSFT", new BigDecimal("3"))), List.of());

        assertThat(desired).containsExactly(captured);
        verify(costBasisCapturer).captureNew("MSFT", new BigDecimal("3"));
    }

    @Test
    void removedTickerIsOmittedFromDesiredState() {
        HoldingSnapshot locked =
                new HoldingSnapshot("AAPL", new BigDecimal("1"), null, null, null, null);
        DesiredHoldingState captured =
                new DesiredHoldingState("MSFT", new BigDecimal("1"), null, null, null, null);
        when(costBasisCapturer.captureNew(eq("MSFT"), eq(new BigDecimal("1")))).thenReturn(captured);

        List<DesiredHoldingState> desired =
                preparer.materialise(
                        List.of(new RawIntent("MSFT", new BigDecimal("1"))), List.of(locked));

        assertThat(desired).extracting(DesiredHoldingState::ticker).containsExactly("MSFT");
    }
}
