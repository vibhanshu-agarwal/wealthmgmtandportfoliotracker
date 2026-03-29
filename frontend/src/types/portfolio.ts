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

// ── Asset allocation (donut chart) ────────────────────────────────────────────

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
