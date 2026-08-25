package com.wealth.portfolio.composition;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable view of a holding row already locked for the composition transaction. */
public record HoldingSnapshot(
        String ticker,
        BigDecimal quantity,
        BigDecimal avgCostBasis,
        String costBasisCurrency,
        String costBasisSource,
        Instant costBasisAsOf) {}
