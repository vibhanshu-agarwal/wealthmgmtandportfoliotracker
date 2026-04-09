package com.wealth.market;

import java.math.BigDecimal;

/**
 * Jackson deserialization target for a single asset entry in the seed fixture file.
 *
 * <p>{@code currency} is included now to align with the {@code quote_currency} pattern used
 * in portfolio-service and to avoid a future breaking schema change.
 */
public record SeedAsset(
        String ticker,
        BigDecimal basePrice,
        String currency
) {}
