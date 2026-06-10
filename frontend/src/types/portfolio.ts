/**
 * Shared TypeScript interfaces mirroring Spring Boot backend DTOs.
 * Keep in sync with: portfolio-context/src/main/java/.../dto/
 */

// ── Asset types ──────────────────────────────────────────────────────────────

export type AssetClass = "STOCK" | "CRYPTO" | "ETF" | "BOND" | "CASH" | "COMMODITY";

/** Canonical display asset class returned by the backend analytics contract (Task 5.4). */
export type DisplayAssetClass = "STOCK" | "CRYPTO" | "ETF" | "BOND" | "CASH" | "COMMODITY" | "OTHER";

export interface AssetHoldingDTO {
  /** Unique holding ID (UUID from backend) */
  id: string;
  /** Exchange ticker symbol, e.g. "AAPL" */
  ticker: string;
  /** Full asset name, e.g. "Apple Inc." */
  name: string;
  /** Asset classification */
  assetClass: AssetClass;
  /** Number of units held */
  quantity: number;
  /** Current market price per unit (USD) */
  currentPrice: number;
  /** quantity × currentPrice */
  totalValue: number;
  /** Average cost per unit at time of purchase; null when basis unavailable */
  avgCostBasis: number | null;
  /** totalValue - (quantity × avgCostBasis); null when basis unavailable */
  unrealizedPnL: number | null;
  /** (unrealizedPnL / totalCostBasis) × 100; null when basis unavailable */
  unrealizedPnLPercent: number | null;
  /** 24-hour price change as a percentage; null when no reference exists */
  change24hPercent: number | null;
  /** 24-hour price change in absolute USD; null when no reference exists */
  change24hAbsolute: number | null;
  /** Portfolio weight as a percentage (0-100) */
  portfolioWeight: number;
  /** ISO-8601 timestamp of last price update */
  lastUpdatedAt: string;
}

// ── Portfolio summary ─────────────────────────────────────────────────────────

export interface PortfolioSummaryDTO {
  totalValue: number;
  totalCostBasis: number;
  totalUnrealizedPnL: number;
  totalUnrealizedPnLPercent: number;
  /** Net 24-hour change in USD */
  change24hAbsolute: number;
  /** Net 24-hour change as a percentage */
  change24hPercent: number;
  /** Ticker of the best performer over 24h */
  bestPerformer: Pick<AssetHoldingDTO, "ticker" | "name" | "change24hPercent">;
  /** Ticker of the worst performer over 24h */
  worstPerformer: Pick<AssetHoldingDTO, "ticker" | "name" | "change24hPercent">;
}

// ── Top-level portfolio response ─────────────────────────────────────────────

export interface PortfolioResponseDTO {
  portfolioId: string;
  ownerId: string;
  name: string;
  currency: string;
  summary: PortfolioSummaryDTO;
  holdings: AssetHoldingDTO[];
  /** ISO-8601 timestamp */
  asOfDate: string;
}

// ── Performance chart ─────────────────────────────────────────────────────────

export interface PerformanceDataPoint {
  /** Date label: "2024-03-01" */
  date: string;
  /** Portfolio total value on that date */
  value: number;
  /** Day-over-day change in USD */
  change: number;
}

export interface PortfolioPerformanceDTO {
  portfolioId: string;
  periodDays: number;
  dataPoints: PerformanceDataPoint[];
  periodReturn: number;
  periodReturnPercent: number;
}

// ── Portfolio Analytics (GET /api/portfolio/analytics) ───────────────────────

/**
 * Per-holding analytics snapshot, all monetary values FX-converted to baseCurrency.
 *
 * Task 5 semantics — nullable fields carry | null to reflect typed-unavailable:
 * - unrealizedPnL / unrealizedPnLPercent: null when no cost basis recorded
 * - change24hAbsolute / change24hPercent: null when no reference in history window
 * - change24hReferenceAt / changeBasis: null when no change reference
 * - displayAssetClass: never null; defaults to "OTHER" for unknown tickers
 */
export interface HoldingAnalyticsDTO {
  /** Exchange ticker symbol */
  ticker: string;
  /** Number of units held */
  quantity: number;
  /** Current market price per unit in quoteCurrency */
  currentPrice: number;
  /** FX-converted total value in baseCurrency */
  currentValueBase: number;
  /** Average cost per unit in costBasisCurrency; null when basis unavailable */
  avgCostBasis: number | null;
  /** ISO currency of avgCostBasis (may differ from quoteCurrency); null when basis absent */
  costBasisCurrency: string | null;
  /** FX-converted unrealised P&L in baseCurrency; null when basis unavailable — never $0.00 for missing data */
  unrealizedPnL: number | null;
  /** Unrealised return as a percentage; null when basis unavailable */
  unrealizedPnLPercent: number | null;
  /** Absolute price change from reference in quoteCurrency; null when no reference exists */
  change24hAbsolute: number | null;
  /** Percentage change from reference; null when no reference exists — never 0.00% for missing data */
  change24hPercent: number | null;
  /** ISO-8601 timestamp of the reference price; null when no reference */
  change24hReferenceAt: string | null;
  /** "WITHIN_24H_WINDOW" | "SINCE_PREVIOUS_SNAPSHOT" | null */
  changeBasis: string | null;
  /** ISO 4217 currency code in which currentPrice is denominated */
  quoteCurrency: string;
  /** Canonical display asset class: "STOCK" | "CRYPTO" | "BOND" | "CASH" | "COMMODITY" | "OTHER" */
  displayAssetClass: DisplayAssetClass;
}

export interface PortfolioAnalyticsDTO {
  /** Sum of all HoldingAnalyticsDTO.currentValueBase in baseCurrency */
  totalValue: number;
  /** Sum of all cost bases in baseCurrency (only holdings with basis included) */
  totalCostBasis: number;
  /** totalValue - totalCostBasis; null when no holdings have a recorded cost basis */
  totalUnrealizedPnL: number | null;
  /** (totalUnrealizedPnL / totalCostBasis) × 100; null when totalUnrealizedPnL is null */
  totalUnrealizedPnLPercent: number | null;
  /** ISO 4217 base currency for all monetary aggregates */
  baseCurrency: string;
  /** true when one or more holdings were excluded because their FX rate was unavailable */
  partialValuation: boolean;
  /** Holding with the highest change24hPercent; change24hPercent is null when no reference */
  bestPerformer: { ticker: string; change24hPercent: number | null };
  /** Holding with the lowest change24hPercent; change24hPercent is null when no reference */
  worstPerformer: { ticker: string; change24hPercent: number | null };
  /** Per-holding analytics snapshots */
  holdings: HoldingAnalyticsDTO[];
  /** Historical performance series, ascending by date */
  performanceSeries: PerformanceDataPoint[];
  /** Coverage metadata for the performance series */
  performanceCoverage: {
    holdingsWithHistory: number;
    totalHoldings: number;
    /** true when some holdings lack history, or when series is synthetic */
    partial: boolean;
    /** true when series is a synthetic placeholder — must not be shown as real portfolio data */
    synthetic: boolean;
  };
}

export interface AllocationSliceDTO {
  assetClass: AssetClass;
  label: string;
  value: number;
  percentage: number;
  /** Hex color for chart rendering */
  color: string;
}

export interface AssetAllocationDTO {
  portfolioId: string;
  totalValue: number;
  slices: AllocationSliceDTO[];
}
