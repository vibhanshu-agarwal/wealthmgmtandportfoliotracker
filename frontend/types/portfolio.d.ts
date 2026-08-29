/** B2 Task 1.16 / Spec A design.md §8 — asset-price freshness only, not whole-valuation. */
export type AssetPriceFreshnessState = "FRESH" | "STALE" | "UNKNOWN" | "MISSING";

export interface AssetPriceFreshnessDTO {
  state: AssetPriceFreshnessState;
  /**
   * Absent — never JSON `null` — when there is no known price observation: an empty
   * portfolio, or one entirely in `MISSING` state. Backend: `AssetPriceFreshnessDto`
   * carries `@JsonInclude(NON_NULL)`, so a `null` Java `Instant` omits the key.
   */
  oldestKnownAssetPriceObservationTimestamp?: string;
  staleHoldings: number;
  unknownPriceHoldings: number;
  missingPriceHoldings: number;
}

export interface PortfolioSummaryDTO {
  userId: string;
  portfolioCount: number;
  totalHoldings: number;
  totalValue: number;
  assetPriceFreshness: AssetPriceFreshnessDTO;
}
