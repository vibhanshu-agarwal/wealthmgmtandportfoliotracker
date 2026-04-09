/**
 * Shared TypeScript interfaces mirroring Spring Boot backend DTOs.
 * Keep in sync with: portfolio-context/src/main/java/.../dto/
 */

// ── Asset types ──────────────────────────────────────────────────────────────

export type AssetClass = "STOCK" | "CRYPTO" | "ETF" | "BOND" | "CASH" | "COMMODITY";

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
  /** Average cost per unit at time of purchase */
  avgCostBasis: number;
  /** totalValue - (quantity × avgCostBasis) */
  unrealizedPnL: number;
  /** 24-hour price change as a percentage */
  change24hPercent: number;
  /** 24-hour price change in absolute USD */
  change24hAbsolute: number;
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

export interface HoldingAnalyticsDTO {
  /** Exchange ticker symbol */
  ticker: string;
  /** Number of units held */
  quantity: number;
  /** Current market price per unit in quoteCurrency */
  currentPrice: number;
  /** FX-converted total value in baseCurrency */
  currentValueBase: number;
  /** Average cost per unit — placeholder equals currentPrice until trade ledger exists */
  avgCostBasis: number;
  /** currentValueBase - (quantity × avgCostBasis × fxRate) */
  unrealizedPnL: number;
  /** currentPrice - price24hAgo (in quoteCurrency) */
  change24hAbsolute: number;
  /** (change24hAbsolute / price24hAgo) × 100, scaled to 4 d.p. */
  change24hPercent: number;
  /** ISO 4217 currency code in which currentPrice is denominated */
  quoteCurrency: string;
}

export interface PortfolioAnalyticsDTO {
  /** Sum of all HoldingAnalyticsDTO.currentValueBase in baseCurrency */
  totalValue: number;
  /** Sum of all cost bases in baseCurrency */
  totalCostBasis: number;
  /** totalValue - totalCostBasis */
  totalUnrealizedPnL: number;
  /** (totalUnrealizedPnL / totalCostBasis) × 100; 0 when totalCostBasis is 0 */
  totalUnrealizedPnLPercent: number;
  /** ISO 4217 base currency for all monetary aggregates */
  baseCurrency: string;
  /** Holding with the highest change24hPercent */
  bestPerformer: { ticker: string; change24hPercent: number };
  /** Holding with the lowest change24hPercent */
  worstPerformer: { ticker: string; change24hPercent: number };
  /** Per-holding analytics snapshots */
  holdings: HoldingAnalyticsDTO[];
  /** Historical performance series, ascending by date */
  performanceSeries: PerformanceDataPoint[];
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
