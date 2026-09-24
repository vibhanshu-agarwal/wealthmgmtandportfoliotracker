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
  /**
   * Number of units held, as a plain-decimal string (B2 Requirement 8.1/8.2).
   *
   * Never a parsed JavaScript number: a `number` has already lost the wire's exact
   * digit count by the time JSON.parse runs, so arithmetic on this field is only
   * permitted after an explicit conversion at a display boundary — see
   * `@/lib/utils/quantityDisplay`.
   */
  quantity: string;
  /**
   * Present and `true` only when this holding's quantity was ingested from a legacy
   * JSON *number* and is therefore display-compatible but not byte-faithful to what
   * portfolio-service stored (B2 Task 2.1). Absent means the value came through as a
   * string and is byte-faithful.
   */
  quantityFidelityUnverified?: boolean;
  /**
   * Current market price per unit, in `quoteCurrency` (not necessarily USD); null when no
   * price is available — never $0.00.
   */
  currentPrice: number | null;
  /**
   * ISO 4217 code `currentPrice` (and `change24hAbsolute`) are quoted in, taken from the same
   * source as the price. Null/absent when that source did not say — render with `QuotePrice`,
   * which then shows no currency symbol rather than assuming "$".
   */
  quoteCurrency?: string | null;
  /** quantity × currentPrice; null when currentPrice is null */
  totalValue: number | null;
  /** Average cost per unit at time of purchase; null when basis unavailable */
  avgCostBasis: number | null;
  /** totalValue - (quantity × avgCostBasis); null when basis unavailable */
  unrealizedPnL: number | null;
  /** (unrealizedPnL / totalCostBasis) × 100; null when basis unavailable */
  unrealizedPnLPercent: number | null;
  /** 24-hour price change as a percentage; null when no reference exists */
  change24hPercent: number | null;
  /** 24-hour price change per unit, in the quote currency; null when no reference exists */
  change24hAbsolute: number | null;
  /**
   * D11: the position's 24h change in base currency, from analytics. Absent or null when
   * unavailable (no analytics record, no reference, no FX rate, or an older backend).
   */
  change24hValueBase?: number | null;
  /** Portfolio weight as a percentage (0-100) */
  portfolioWeight: number;
  /** ISO-8601 timestamp of last price update */
  lastUpdatedAt: string;
  /** From analytics when merged: why the 24h change is what it is (see HoldingAnalyticsDTO). */
  changeBasis?: string | null;
  /** From analytics when merged: when the current price was observed (ISO-8601, UTC). */
  priceObservedAt?: string | null;
}

// ── Portfolio summary ─────────────────────────────────────────────────────────

export interface PortfolioSummaryDTO {
  totalValue: number;
  /**
   * True when totalValue leaves out some holding: no price, or (on the client-assembled path,
   * which has no FX step) a price not in the base currency. Absent means not known to be partial.
   */
  partialValuation?: boolean;
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
  /**
   * Optimistic-concurrency version observed on this read (B2 Task 1.2).
   *
   * `0` is the valid no-portfolio state (B2 requirements.md 4.5) — B1 auto-provisions
   * on expected version 0. A composition save carries the version observed when the
   * picker opened, never one re-read inside the save itself (GC.6).
   */
  version: number;
  /**
   * Whether `version` above was genuinely read from the backend's own `version`
   * field, as opposed to a client-side default applied because the field was
   * absent (`fetchPortfolio`'s `?? 0`, currently-deployed backends that predate
   * versioning). Absent/`undefined` is treated as observed by any caller that
   * doesn't check it — every existing caller only ever supplies a genuine
   * PUT-response version here, never a defaulted one.
   */
  versionObserved?: boolean;
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
 * Task 5 semantics — nullable fields carry | null to reflect typed-unavailable
 * (the wire contract is PortfolioAnalyticsDto.HoldingAnalyticsDto in portfolio-service):
 * - currentPrice and quoteCurrency: null when the ticker has no market price
 * - currentValueBase: null when the price or the quote-currency FX rate is unavailable
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
  /** Current market price per unit in quoteCurrency; null when the ticker has no price */
  currentPrice: number | null;
  /** FX-converted total value in baseCurrency; null when the price or FX rate is unavailable */
  currentValueBase: number | null;
  /** Average cost per unit in costBasisCurrency; null when basis unavailable */
  avgCostBasis: number | null;
  /** ISO currency of avgCostBasis (may differ from quoteCurrency); null when basis absent */
  costBasisCurrency: string | null;
  /** FX-converted unrealised P&L in baseCurrency; null when basis unavailable — never $0.00 for missing data */
  unrealizedPnL: number | null;
  /** Unrealised return as a percentage; null when basis unavailable */
  unrealizedPnLPercent: number | null;
  /** Per-unit price change from reference in quoteCurrency; null when no reference exists */
  change24hAbsolute: number | null;
  /** Percentage change from reference; null when no reference exists — never 0.00% for missing data */
  change24hPercent: number | null;
  /**
   * D11: the position's 24h change in baseCurrency, quantity × per-unit change × current FX rate.
   * Null when the change or the FX rate is unavailable; absent from an older backend.
   */
  change24hValueBase?: number | null;
  /** ISO-8601 timestamp of the reference price; null when no reference */
  change24hReferenceAt: string | null;
  /**
   * "WITHIN_24H_WINDOW" | "SINCE_PREVIOUS_SNAPSHOT" | "STALE_PRICE" | null. "STALE_PRICE" means
   * the price is older than the freshness threshold, so every 24h change field is null.
   */
  changeBasis: string | null;
  /** ISO 4217 currency code in which currentPrice is denominated; null when there is no price */
  quoteCurrency: string | null;
  /** Canonical display asset class: "STOCK" | "CRYPTO" | "BOND" | "CASH" | "COMMODITY" | "OTHER" */
  displayAssetClass: DisplayAssetClass;
  /** When the current price was observed (ISO-8601, UTC); absent from an older backend. */
  priceObservedAt?: string | null;
  /** The summary banner's freshness rule applied to this holding; absent from an older backend. */
  priceFreshness?: "FRESH" | "STALE" | "UNKNOWN" | "MISSING" | null;
}

/** changeBasis for a holding whose price is too old to have a 24h change (rehearsal defect #2). */
export const STALE_PRICE_BASIS = "STALE_PRICE";

/** D11: coverage of the analytics 24h totals. */
export interface Change24hCoverage {
  /** Holdings contributing a position-level 24h change to the totals */
  holdingsWithChange: number;
  /** Holdings counted in totalValue */
  countedHoldings: number;
  /** Every holding, including those without a price or FX rate */
  totalHoldings: number;
  /** holdingsWithChange < totalHoldings: the totals describe only part of the portfolio */
  partial: boolean;
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
  /**
   * D11: Σ change24hValueBase over the holdings counted in totalValue. Null when none has a 24h
   * change; absent from an older backend. Either way the UI shows "—".
   */
  totalChange24hBase?: number | null;
  /** D11: totalChange24hBase as a percentage of those holdings' value at the reference; nullable */
  totalChange24hPercent?: number | null;
  /**
   * D11: which holdings the 24h totals cover. Absent from an older backend, in which case the UI
   * shows no 24h total at all rather than imply a complete one.
   */
  change24hCoverage?: Change24hCoverage | null;
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
  /** Display asset class including "OTHER" for unknown/unclassified holdings */
  assetClass: DisplayAssetClass;
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
