package com.wealth.portfolio.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Expands ticker/quantity intent against the locked snapshot: retained tickers keep their cost-basis
 * tuple; new tickers capture add-time basis. No weighted-average inference.
 */
@Component
public class CompositionTuplePreparer implements TuplePreparer {

    private final AddTimeCostBasisCapturer costBasisCapturer;

    public CompositionTuplePreparer(AddTimeCostBasisCapturer costBasisCapturer) {
        this.costBasisCapturer = costBasisCapturer;
    }

    @Override
    public List<DesiredHoldingState> materialise(
            List<RawIntent> intent, List<HoldingSnapshot> lockedSnapshot) {
        Map<String, HoldingSnapshot> byTicker =
                lockedSnapshot.stream()
                        .collect(
                                Collectors.toMap(
                                        HoldingSnapshot::ticker,
                                        Function.identity(),
                                        (a, b) -> a));

        List<DesiredHoldingState> desired = new ArrayList<>(intent.size());
        for (RawIntent item : intent) {
            HoldingSnapshot existing = byTicker.get(item.ticker());
            if (existing != null) {
                desired.add(
                        new DesiredHoldingState(
                                item.ticker(),
                                item.quantity(),
                                existing.avgCostBasis(),
                                existing.costBasisCurrency(),
                                existing.costBasisSource(),
                                existing.costBasisAsOf()));
            } else {
                desired.add(costBasisCapturer.captureNew(item.ticker(), item.quantity()));
            }
        }
        return List.copyOf(desired);
    }
}
