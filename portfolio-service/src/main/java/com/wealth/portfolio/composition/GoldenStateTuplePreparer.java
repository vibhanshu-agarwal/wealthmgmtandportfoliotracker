package com.wealth.portfolio.composition;

import com.wealth.portfolio.seed.DeterministicPriceCalculator;
import com.wealth.portfolio.seed.PortfolioSeedService;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supplies deterministic full tuples for identity-preserving reset / golden-state seed. The
 * cost-basis anchor is an input — never a hardcoded moving 25-hour value.
 *
 * <p>Not a Spring bean: constructed per invocation with the target user and anchor.
 */
public final class GoldenStateTuplePreparer implements TuplePreparer {

    private static final int QUANTITY_RANGE = 50;

    private final SeedTickerRegistry registry;
    private final String userId;
    private final Instant costBasisAnchor;

    public GoldenStateTuplePreparer(
            SeedTickerRegistry registry, String userId, Instant costBasisAnchor) {
        this.registry = registry;
        this.userId = userId;
        this.costBasisAnchor = costBasisAnchor;
    }

    @Override
    public List<DesiredHoldingState> materialise(
            List<RawIntent> intent, List<HoldingSnapshot> lockedSnapshot) {
        Map<String, SeedTicker> active =
                registry.active().stream()
                        .collect(Collectors.toMap(SeedTicker::ticker, Function.identity()));

        // Prefer intent order when supplied; otherwise emit the full active catalog.
        List<RawIntent> source =
                intent == null || intent.isEmpty()
                        ? registry.active().stream()
                                .map(
                                        t ->
                                                new RawIntent(
                                                        t.ticker(),
                                                        BigDecimal.valueOf(quantityFor(t.ticker()))))
                                .toList()
                        : intent;

        List<DesiredHoldingState> desired = new ArrayList<>(source.size());
        for (RawIntent item : source) {
            SeedTicker ticker = active.get(item.ticker());
            if (ticker == null) {
                throw new IllegalArgumentException(
                        "GoldenStateTuplePreparer requires an active catalog ticker: "
                                + item.ticker());
            }
            desired.add(toDesired(ticker, item.quantity()));
        }
        return List.copyOf(desired);
    }

    private DesiredHoldingState toDesired(SeedTicker t, BigDecimal quantity) {
        BigDecimal seedPrice =
                DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId);
        BigDecimal costBasis =
                PortfolioSeedService.computeDeterministicCostBasis(seedPrice, t.ticker(), userId);
        return new DesiredHoldingState(
                t.ticker(),
                quantity,
                costBasis,
                t.quoteCurrency(),
                "SEED",
                costBasisAnchor);
    }

    private static int quantityFor(String ticker) {
        return Math.floorMod(ticker.hashCode(), QUANTITY_RANGE) + 1;
    }
}
