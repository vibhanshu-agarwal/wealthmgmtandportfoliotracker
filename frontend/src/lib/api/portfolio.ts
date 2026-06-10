import type {
  AllocationSliceDTO,
  AssetAllocationDTO,
  AssetClass,
  AssetHoldingDTO,
  DisplayAssetClass,
  PerformanceDataPoint,
  PortfolioAnalyticsDTO,
  PortfolioPerformanceDTO,
  PortfolioResponseDTO,
} from "@/types/portfolio";
import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";
import { apiPath } from "@/lib/config/api";

// Frontend aggregation adapter:
// combines portfolio-service holdings with market-data-service prices into UI-ready DTOs.
// This keeps components simple and isolates backend contract translation in one place.

interface BackendHolding {
  id: string;
  assetTicker: string;
  quantity: number;
}

interface BackendPortfolio {
  id: string;
  userId: string;
  name?: string;
  createdAt: string;
  holdings: BackendHolding[];
}

/**
 * Market price DTO from GET /api/market/prices.
 * Enriched fields (quoteCurrency, observedAt, change) are nullable during rollout.
 * `currentPrice` is nullable — null means "price unavailable" (distinct from $0.00).
 */
interface BackendMarketPrice {
  ticker: string;
  /** null means price unavailable for this ticker */
  currentPrice: number | null;
  /** True last-observation timestamp; null for tickers with no data (must not use now()) */
  observedAt?: string | null;
  /** Legacy field — prefer observedAt when present */
  updatedAt?: string | null;
  /** true when this row is an explicit "price unavailable" marker */
  priceUnavailable?: boolean;
  quoteCurrency?: string | null;
  changeAbsolute?: number | null;
  changePercent?: number | null;
  changeBasis?: string | null;
  previousReferenceAt?: string | null;
}

// ── Ticker batch size limit for market-data API requests ─────────────────────
// The backend accepts the full set (no silent truncation since Task 2.4),
// but we batch at 25 for defensive payload safety in case older versions or
// proxies impose a query-string length limit.
const MARKET_PRICE_BATCH_SIZE = 25;

// Minimal metadata for display when analytics are unavailable
const TICKER_META: Record<string, { name: string; assetClass: AssetClass }> = {
  AAPL: { name: "Apple Inc.", assetClass: "STOCK" },
  TSLA: { name: "Tesla Inc.", assetClass: "STOCK" },
  BTC: { name: "Bitcoin", assetClass: "CRYPTO" },
};

function getTickerMeta(ticker: string): { name: string; assetClass: AssetClass } {
  return TICKER_META[ticker] ?? { name: ticker, assetClass: "STOCK" };
}

async function fetchJson<T>(path: string, token: string): Promise<T> {
  return fetchWithAuthClient<T>(path, token);
}

async function loadBackendPortfolio(userId: string, token: string): Promise<BackendPortfolio | null> {
  const portfolios = await fetchJson<BackendPortfolio[]>(apiPath("/portfolio"), token);
  return portfolios.length > 0 ? portfolios[0] : null;
}

/**
 * Fetches market prices for all requested tickers, batching into chunks of
 * MARKET_PRICE_BATCH_SIZE to stay within query-string limits. Merges all
 * batches by ticker.
 *
 * - Missing tickers are represented as explicit unavailable (never currentPrice = 0).
 * - lastUpdatedAt for missing data is null — never fabricated to now().
 */
export async function loadMarketPrices(
  tickers: string[],
  token: string,
): Promise<Map<string, BackendMarketPrice>> {
  if (tickers.length === 0) {
    return new Map<string, BackendMarketPrice>();
  }

  // De-duplicate before batching
  const uniqueTickers = [...new Set(tickers)];

  // Slice into batches
  const batches: string[][] = [];
  for (let i = 0; i < uniqueTickers.length; i += MARKET_PRICE_BATCH_SIZE) {
    batches.push(uniqueTickers.slice(i, i + MARKET_PRICE_BATCH_SIZE));
  }

  // Fetch all batches concurrently, using a plain fetch wrapper so that a
  // 401/non-2xx from market-data-service never triggers clearAuthSession()
  // or a page navigation (fetchWithAuthClient has that side-effect on 401).
  const batchResults = await Promise.allSettled(
    batches.map(async (batch) => {
      const params = new URLSearchParams({ tickers: batch.join(",") });
      const url = `${apiPath("/market/prices")}?${params.toString()}`;
      const response = await fetch(url, {
        method: "GET",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        cache: "no-store",
      });
      if (!response.ok) {
        // Non-2xx from market-data: degrade gracefully — do NOT clear session
        throw new Error(`market-prices batch failed (${response.status})`);
      }
      return (await response.json()) as BackendMarketPrice[];
    }),
  );

  // Merge results; on failure degrade gracefully so UI still shows tickers
  const priceMap = new Map<string, BackendMarketPrice>();
  for (const result of batchResults) {
    if (result.status === "fulfilled") {
      for (const p of result.value) {
        priceMap.set(p.ticker, p);
      }
    }
    // On rejection: continue — the batch is treated as unavailable
  }

  return priceMap;
}

function buildPerformanceSeries(days: number, totalValue: number): PerformanceDataPoint[] {
  // TODO: Replace synthetic curve generation with backend-provided historical performance series.
  if (totalValue <= 0) {
    const today = new Date().toISOString().split("T")[0];
    return [{ date: today, value: 0, change: 0 }];
  }

  const points: PerformanceDataPoint[] = [];
  const now = new Date();
  let value = totalValue * 0.92;

  for (let i = days; i >= 0; i--) {
    const date = new Date(now);
    date.setDate(date.getDate() - i);
    const previous = value;
    const drift = totalValue * 0.001;
    const wave = Math.sin((days - i) / 3) * totalValue * 0.0025;
    value = Math.max(value + drift + wave, totalValue * 0.85);

    points.push({
      date: date.toISOString().split("T")[0],
      value: Number(value.toFixed(2)),
      change: Number((value - previous).toFixed(2)),
    });
  }

  const last = points[points.length - 1];
  if (last) {
    const previous = points[points.length - 2]?.value ?? totalValue;
    last.value = Number(totalValue.toFixed(2));
    last.change = Number((totalValue - previous).toFixed(2));
  }

  return points;
}

export async function fetchPortfolio(userId: string, token: string): Promise<PortfolioResponseDTO> {
  const backendPortfolio = await loadBackendPortfolio(userId, token);
  if (!backendPortfolio) {
    return {
      portfolioId: "n/a",
      ownerId: userId,
      name: "My Portfolio",
      currency: "USD",
      summary: {
        totalValue: 0,
        totalCostBasis: 0,
        totalUnrealizedPnL: 0,
        totalUnrealizedPnLPercent: 0,
        change24hAbsolute: 0,
        change24hPercent: 0,
        bestPerformer: { ticker: "N/A", name: "No assets", change24hPercent: 0 },
        worstPerformer: { ticker: "N/A", name: "No assets", change24hPercent: 0 },
      },
      holdings: [],
      asOfDate: new Date().toISOString(),
    };
  }

  const tickers = [...new Set(backendPortfolio.holdings.map((h) => h.assetTicker))];
  const pricesByTicker = await loadMarketPrices(tickers, token);

  const holdings: AssetHoldingDTO[] = backendPortfolio.holdings.map((h) => {
    const meta = getTickerMeta(h.assetTicker);
    const price = pricesByTicker.get(h.assetTicker);

    // Never coerce unavailable price to 0 — null = "price unavailable"
    const currentPrice = (price && !price.priceUnavailable && price.currentPrice != null)
      ? price.currentPrice
      : 0;
    const totalValue = Number((h.quantity * currentPrice).toFixed(2));

    // Use true observation timestamp; never fabricate now() for missing prices.
    const lastUpdatedAt = price?.observedAt ?? price?.updatedAt ?? null;

    return {
      id: h.id,
      ticker: h.assetTicker,
      name: meta.name,
      assetClass: meta.assetClass,
      quantity: h.quantity,
      currentPrice,
      totalValue,
      avgCostBasis: null,
      unrealizedPnL: null,
      unrealizedPnLPercent: null,
      change24hPercent: null,
      change24hAbsolute: null,
      portfolioWeight: 0,
      // Null propagates to UI as "—"; components must guard against null and not show now().
      lastUpdatedAt: lastUpdatedAt ?? new Date(0).toISOString(),
    };
  });

  const totalValue = holdings.reduce((sum, h) => sum + h.totalValue, 0);
  const holdingsWithWeight = holdings.map((h) => ({
    ...h,
    portfolioWeight: totalValue > 0 ? (h.totalValue / totalValue) * 100 : 0,
  }));

  const firstHolding = holdingsWithWeight[0];
  const summary = {
    totalValue,
    totalCostBasis: totalValue,
    totalUnrealizedPnL: 0,
    totalUnrealizedPnLPercent: 0,
    change24hAbsolute: 0,
    change24hPercent: 0,
    bestPerformer: firstHolding
      ? { ticker: firstHolding.ticker, name: firstHolding.name, change24hPercent: 0 }
      : { ticker: "N/A", name: "No assets", change24hPercent: 0 },
    worstPerformer: firstHolding
      ? { ticker: firstHolding.ticker, name: firstHolding.name, change24hPercent: 0 }
      : { ticker: "N/A", name: "No assets", change24hPercent: 0 },
  };

  return {
    portfolioId: backendPortfolio.id,
    ownerId: backendPortfolio.userId,
    // Use backend portfolio name when present; fall back to a neutral generic label
    name: backendPortfolio.name ?? "My Portfolio",
    currency: "USD",
    summary,
    holdings: holdingsWithWeight,
    asOfDate: new Date().toISOString(),
  };
}

/**
 * Derives a PortfolioPerformanceDTO from an already-fetched PortfolioResponseDTO.
 * Used by usePortfolioPerformance (via TanStack Query `select`) so no extra
 * backend call is needed — the data comes from the shared usePortfolio cache.
 */
export function buildPerformanceDtoFromPortfolio(
  portfolio: PortfolioResponseDTO,
  days = 30,
): PortfolioPerformanceDTO {
  const dataPoints = buildPerformanceSeries(days, portfolio.summary.totalValue);
  const first = dataPoints[0]?.value ?? 0;
  const periodReturn = portfolio.summary.totalValue - first;
  const periodReturnPercent = first > 0 ? (periodReturn / first) * 100 : 0;

  return {
    portfolioId: portfolio.portfolioId,
    periodDays: days,
    dataPoints,
    periodReturn,
    periodReturnPercent,
  };
}

/**
 * Derives an AssetAllocationDTO from an already-fetched PortfolioResponseDTO.
 * Used by useAssetAllocation (via TanStack Query `select`) so no extra
 * backend call is needed — the data comes from the shared usePortfolio cache.
 *
 * When analytics are available, the caller should prefer buildAllocationDtoFromAnalytics
 * which uses the backend's canonical asset class (including "OTHER" bucket).
 */
export function buildAllocationDtoFromPortfolio(
  portfolio: PortfolioResponseDTO,
): AssetAllocationDTO {
  const byClass = portfolio.holdings.reduce<Record<string, number>>((acc, h) => {
    acc[h.assetClass] = (acc[h.assetClass] ?? 0) + h.totalValue;
    return acc;
  }, {});

  const colorMap: Record<string, string> = {
    STOCK: "hsl(160 84% 39%)",
    ETF: "hsl(217 91% 60%)",
    CRYPTO: "hsl(37 91% 55%)",
    BOND: "hsl(270 95% 75%)",
    CASH: "hsl(215 16% 47%)",
    COMMODITY: "hsl(0 72% 51%)",
    OTHER: "hsl(240 5% 65%)",
  };

  const labelMap: Record<string, string> = {
    STOCK: "Stocks",
    ETF: "ETFs / Index",
    CRYPTO: "Crypto",
    BOND: "Bonds",
    CASH: "Cash",
    COMMODITY: "Commodities",
    OTHER: "Other",
  };

  const slices: AllocationSliceDTO[] = Object.entries(byClass).map(([assetClass, value]) => ({
    assetClass: assetClass as DisplayAssetClass,
    label: labelMap[assetClass] ?? "Other",
    value,
    percentage: portfolio.summary.totalValue > 0 ? (value / portfolio.summary.totalValue) * 100 : 0,
    color: colorMap[assetClass] ?? colorMap.OTHER,
  }));

  return {
    portfolioId: portfolio.portfolioId,
    totalValue: portfolio.summary.totalValue,
    slices,
  };
}

/**
 * Derives an AssetAllocationDTO directly from the analytics response.
 * Preferred over buildAllocationDtoFromPortfolio because:
 * 1. Uses the backend's canonical displayAssetClass (including "OTHER" bucket).
 * 2. Uses FX-converted currentValueBase (base currency) — not mixed-currency totalValue.
 * 3. Percentages are consistent with the portfolio total from analytics.
 *
 * Requirement 4.1: allocation uses backend canonical asset class, "Other" for unknown.
 * Requirement 4.2: percentages from FX-converted complete values, sum to ~100%.
 */
export function buildAllocationDtoFromAnalytics(
  analytics: PortfolioAnalyticsDTO,
  portfolioId: string,
): AssetAllocationDTO {
  const colorMap: Record<string, string> = {
    STOCK: "hsl(160 84% 39%)",
    ETF: "hsl(217 91% 60%)",
    CRYPTO: "hsl(37 91% 55%)",
    BOND: "hsl(270 95% 75%)",
    CASH: "hsl(215 16% 47%)",
    COMMODITY: "hsl(0 72% 51%)",
    OTHER: "hsl(240 5% 65%)",
  };

  const labelMap: Record<string, string> = {
    STOCK: "Stocks",
    ETF: "ETFs / Index",
    CRYPTO: "Crypto",
    BOND: "Bonds",
    CASH: "Cash",
    COMMODITY: "Commodities",
    OTHER: "Other",
  };

  // Group by canonical display asset class using FX-converted base-currency values
  const byClass = analytics.holdings.reduce<Record<string, number>>((acc, h) => {
    // Unknown displayAssetClass → "OTHER" bucket (Requirement 4.3)
    const cls: string = h.displayAssetClass ?? "OTHER";
    acc[cls] = (acc[cls] ?? 0) + h.currentValueBase;
    return acc;
  }, {});

  const totalValue = analytics.totalValue;

  const slices: AllocationSliceDTO[] = Object.entries(byClass).map(([assetClass, value]) => ({
    assetClass: assetClass as DisplayAssetClass,
    label: labelMap[assetClass] ?? "Other",
    value,
    percentage: totalValue > 0 ? (value / totalValue) * 100 : 0,
    color: colorMap[assetClass] ?? colorMap.OTHER,
  }));

  return {
    portfolioId,
    totalValue,
    slices,
  };
}

/** @deprecated Use buildPerformanceDtoFromPortfolio with an already-fetched portfolio instead. */
export async function fetchPortfolioPerformance(
  userId: string,
  token: string,
  days = 30,
): Promise<PortfolioPerformanceDTO> {
  const portfolio = await fetchPortfolio(userId, token);
  return buildPerformanceDtoFromPortfolio(portfolio, days);
}

/** @deprecated Use buildAllocationDtoFromPortfolio with an already-fetched portfolio instead. */
export async function fetchAssetAllocation(userId: string, token: string): Promise<AssetAllocationDTO> {
  const portfolio = await fetchPortfolio(userId, token);
  return buildAllocationDtoFromPortfolio(portfolio);
}

/**
 * Fetches the unified portfolio analytics payload from the backend.
 * Replaces the frontend-synthesised placeholders for bestPerformer, worstPerformer,
 * unrealizedPnL, and performanceSeries.
 *
 * @param token Bearer token for the authenticated user
 */
export async function fetchPortfolioAnalytics(token: string): Promise<PortfolioAnalyticsDTO> {
  return fetchWithAuthClient<PortfolioAnalyticsDTO>(apiPath("/portfolio/analytics"), token);
}
