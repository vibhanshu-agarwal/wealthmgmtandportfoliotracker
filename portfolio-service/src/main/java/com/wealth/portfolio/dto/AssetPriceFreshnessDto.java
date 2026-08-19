package com.wealth.portfolio.dto;

import com.wealth.portfolio.freshness.FreshnessState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Asset-price freshness only — not whole-valuation freshness. FX rate age is out of scope
 * (Requirement 9.46, 9.47).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetPriceFreshnessDto(
        FreshnessState state,
        Instant oldestKnownAssetPriceObservationTimestamp,
        int staleHoldings,
        int unknownPriceHoldings,
        int missingPriceHoldings) {

    public static AssetPriceFreshnessDto emptyPortfolio() {
        return new AssetPriceFreshnessDto(FreshnessState.FRESH, null, 0, 0, 0);
    }
}
