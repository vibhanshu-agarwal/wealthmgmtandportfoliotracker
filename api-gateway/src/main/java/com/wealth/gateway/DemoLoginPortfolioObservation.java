package com.wealth.gateway;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** The single version-bearing GET observation used for a later reset, never reread. */
public record DemoLoginPortfolioObservation(
        UUID portfolioId,
        String userId,
        Instant updatedAt,
        long version,
        boolean idleEligible,
        URI target,
        boolean originVerifyRequired,
        boolean originVerifyAttached
) {
    public boolean isIdleEligible() {
        return idleEligible;
    }
}
