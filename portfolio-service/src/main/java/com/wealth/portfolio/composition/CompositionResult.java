package com.wealth.portfolio.composition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of a composition replacement. Carries version without mutating Wave 4b's
 * {@code PortfolioResponse} contract (task 4.10).
 */
public record CompositionResult(
        UUID portfolioId,
        String userId,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<DesiredHoldingState> holdings,
        boolean created,
        boolean noOp) {}
