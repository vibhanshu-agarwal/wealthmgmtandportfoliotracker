/**
 * Portfolio API service layer.
 *
 * All functions return Promises and simulate network latency so that
 * loading states are testable in the UI. When the backend is live,
 * swap `mockFetch` calls for real `fetch` calls to NEXT_PUBLIC_API_BASE_URL.
 */

import type {
  AssetAllocationDTO,
  AssetHoldingDTO,
  PortfolioPerformanceDTO,
  PortfolioResponseDTO,
} from "@/types/portfolio";

// ── Config ───────────────────────────────────────────────────────────────────

// const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Simulated network round-trip (ms). Set to 0 for instant mock responses. */
const MOCK_DELAY_MS = 800;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ── Mock data ────────────────────────────────────────────────────────────────

const MOCK_HOLDINGS: AssetHoldingDTO[] = [
  {
    id: "h-001",
    ticker: "AAPL",
    name: "Apple Inc.",
    assetClass: "STOCK",
    quantity: 120,
    currentPrice: 189.72,
    totalValue: 22_766.4,
    avgCostBasis: 152.3,
    unrealizedPnL: 4_490.4,
    change24hPercent: 0.83,
    change24hAbsolute: 1.56,
    portfolioWeight: 8.01,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-002",
    ticker: "MSFT",
    name: "Microsoft Corporation",
    assetClass: "STOCK",
    quantity: 80,
    currentPrice: 415.3,
    totalValue: 33_224.0,
    avgCostBasis: 310.5,
    unrealizedPnL: 8_384.0,
    change24hPercent: 1.56,
    change24hAbsolute: 6.38,
    portfolioWeight: 11.68,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-003",
    ticker: "NVDA",
    name: "NVIDIA Corporation",
    assetClass: "STOCK",
    quantity: 45,
    currentPrice: 875.4,
    totalValue: 39_393.0,
    avgCostBasis: 420.0,
    unrealizedPnL: 20_493.0,
    change24hPercent: 3.21,
    change24hAbsolute: 27.26,
    portfolioWeight: 13.85,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-004",
    ticker: "GOOGL",
    name: "Alphabet Inc.",
    assetClass: "STOCK",
    quantity: 60,
    currentPrice: 173.55,
    totalValue: 10_413.0,
    avgCostBasis: 140.2,
    unrealizedPnL: 2_001.0,
    change24hPercent: -0.31,
    change24hAbsolute: -0.54,
    portfolioWeight: 3.66,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-005",
    ticker: "VOO",
    name: "Vanguard S&P 500 ETF",
    assetClass: "ETF",
    quantity: 85,
    currentPrice: 493.5,
    totalValue: 41_947.5,
    avgCostBasis: 380.0,
    unrealizedPnL: 9_647.5,
    change24hPercent: 0.42,
    change24hAbsolute: 2.07,
    portfolioWeight: 14.75,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-006",
    ticker: "BTC",
    name: "Bitcoin",
    assetClass: "CRYPTO",
    quantity: 1.5,
    currentPrice: 67_420.5,
    totalValue: 101_130.75,
    avgCostBasis: 42_000.0,
    unrealizedPnL: 38_130.75,
    change24hPercent: -2.14,
    change24hAbsolute: -1_475.68,
    portfolioWeight: 35.55,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-007",
    ticker: "ETH",
    name: "Ethereum",
    assetClass: "CRYPTO",
    quantity: 8,
    currentPrice: 3_850.0,
    totalValue: 30_800.0,
    avgCostBasis: 2_200.0,
    unrealizedPnL: 13_200.0,
    change24hPercent: 1.18,
    change24hAbsolute: 44.87,
    portfolioWeight: 10.83,
    lastUpdatedAt: new Date().toISOString(),
  },
  {
    id: "h-008",
    ticker: "CASH",
    name: "Cash & Equivalents",
    assetClass: "CASH",
    quantity: 1,
    currentPrice: 5_000.0,
    totalValue: 5_000.0,
    avgCostBasis: 5_000.0,
    unrealizedPnL: 0,
    change24hPercent: 0,
    change24hAbsolute: 0,
    portfolioWeight: 1.76,
    lastUpdatedAt: new Date().toISOString(),
  },
];

const TOTAL_VALUE = MOCK_HOLDINGS.reduce((sum, h) => sum + h.totalValue, 0);
const TOTAL_COST  = MOCK_HOLDINGS.reduce((sum, h) => sum + h.avgCostBasis * h.quantity, 0);

const MOCK_PORTFOLIO: PortfolioResponseDTO = {
  portfolioId: "p-001",
  ownerId: "u-001",
  name: "Main Portfolio",
  currency: "USD",
  summary: {
    totalValue: TOTAL_VALUE,
    totalCostBasis: TOTAL_COST,
    totalUnrealizedPnL: TOTAL_VALUE - TOTAL_COST,
    totalUnrealizedPnLPercent: ((TOTAL_VALUE - TOTAL_COST) / TOTAL_COST) * 100,
    change24hAbsolute: 2_847.62,
    change24hPercent: 1.01,
    bestPerformer: { ticker: "NVDA", name: "NVIDIA Corporation", change24hPercent: 3.21 },
    worstPerformer: { ticker: "BTC",  name: "Bitcoin",            change24hPercent: -2.14 },
  },
  holdings: MOCK_HOLDINGS,
  asOfDate: new Date().toISOString(),
};

/** Generates a realistic 30-day value curve with some volatility. */
function generatePerformanceData(days: number, endValue: number): PortfolioPerformanceDTO["dataPoints"] {
  const points = [];
  let value = endValue * 0.87; // start ~13% lower
  const now = new Date();

  for (let i = days; i >= 0; i--) {
    const date = new Date(now);
    date.setDate(date.getDate() - i);

    // Add realistic daily drift: slight upward trend + random noise
    const drift   = endValue * 0.0015;
    const noise   = (Math.random() - 0.45) * endValue * 0.008;
    const prevVal = value;
    value = Math.max(value + drift + noise, endValue * 0.8);

    points.push({
      date: date.toISOString().split("T")[0],
      value: Math.round(value * 100) / 100,
      change: Math.round((value - prevVal) * 100) / 100,
    });
  }

  // Force last point to match current total
  if (points.length > 0) {
    const last = points[points.length - 1];
    last.value  = endValue;
    last.change = endValue - (points[points.length - 2]?.value ?? 0);
  }

  return points;
}

// ── API functions ─────────────────────────────────────────────────────────────

/**
 * Fetch the full portfolio (holdings + summary).
 * TODO: replace body with `fetch(`${API_BASE}/api/v1/portfolios/${portfolioId}`)` when backend is live.
 */
export async function fetchPortfolio(_portfolioId = "p-001"): Promise<PortfolioResponseDTO> {
  await sleep(MOCK_DELAY_MS);
  // Real implementation:
  // const res = await fetch(`${API_BASE}/api/v1/portfolios/${portfolioId}`);
  // if (!res.ok) throw new Error(`Portfolio fetch failed: ${res.status}`);
  // return res.json() as Promise<PortfolioResponseDTO>;
  return structuredClone(MOCK_PORTFOLIO);
}

/**
 * Fetch N-day historical performance for a portfolio.
 * TODO: `GET /api/v1/portfolios/{id}/performance?days={days}`
 */
export async function fetchPortfolioPerformance(
  portfolioId = "p-001",
  days = 30
): Promise<PortfolioPerformanceDTO> {
  await sleep(MOCK_DELAY_MS);
  const dataPoints = generatePerformanceData(days, TOTAL_VALUE);
  const firstValue = dataPoints[0]?.value ?? TOTAL_VALUE;

  return {
    portfolioId,
    periodDays: days,
    dataPoints,
    periodReturn: TOTAL_VALUE - firstValue,
    periodReturnPercent: ((TOTAL_VALUE - firstValue) / firstValue) * 100,
  };
}

/**
 * Fetch asset class allocation breakdown.
 * TODO: `GET /api/v1/portfolios/{id}/allocation`
 */
export async function fetchAssetAllocation(portfolioId = "p-001"): Promise<AssetAllocationDTO> {
  await sleep(MOCK_DELAY_MS);

  // Aggregate by asset class
  const byClass = MOCK_HOLDINGS.reduce<Record<string, number>>((acc, h) => {
    acc[h.assetClass] = (acc[h.assetClass] ?? 0) + h.totalValue;
    return acc;
  }, {});

  // Chart color tokens (must match --chart-N CSS variables)
  const COLOR_MAP: Record<string, string> = {
    STOCK:     "hsl(160 84% 39%)",  // chart-1 emerald
    ETF:       "hsl(217 91% 60%)",  // chart-2 blue
    CRYPTO:    "hsl(37 91% 55%)",   // chart-3 amber
    BOND:      "hsl(270 95% 75%)",  // chart-4 violet
    CASH:      "hsl(215 16% 47%)",  // chart-5 slate
    COMMODITY: "hsl(0 72% 51%)",
  };

  const LABEL_MAP: Record<string, string> = {
    STOCK: "Stocks", ETF: "ETFs / Index", CRYPTO: "Crypto",
    BOND: "Bonds", CASH: "Cash", COMMODITY: "Commodities",
  };

  const slices = Object.entries(byClass).map(([assetClass, value]) => ({
    assetClass: assetClass as AssetAllocationDTO["slices"][number]["assetClass"],
    label: LABEL_MAP[assetClass] ?? assetClass,
    value,
    percentage: (value / TOTAL_VALUE) * 100,
    color: COLOR_MAP[assetClass] ?? "#888",
  }));

  return { portfolioId, totalValue: TOTAL_VALUE, slices };
}
