import type {
  AllocationSliceDTO,
  AssetAllocationDTO,
  AssetClass,
  AssetHoldingDTO,
  PerformanceDataPoint,
  PortfolioPerformanceDTO,
  PortfolioResponseDTO,
} from "@/types/portfolio";

interface BackendHolding {
  id: string;
  assetTicker: string;
  quantity: number;
}

interface BackendPortfolio {
  id: string;
  userId: string;
  createdAt: string;
  holdings: BackendHolding[];
}

interface BackendMarketPrice {
  ticker: string;
  currentPrice: number;
  updatedAt: string;
}

const TICKER_META: Record<string, { name: string; assetClass: AssetClass }> = {
  AAPL: { name: "Apple Inc.", assetClass: "STOCK" },
  TSLA: { name: "Tesla Inc.", assetClass: "STOCK" },
  BTC: { name: "Bitcoin", assetClass: "CRYPTO" },
};

function getTickerMeta(ticker: string): { name: string; assetClass: AssetClass } {
  return TICKER_META[ticker] ?? { name: ticker, assetClass: "STOCK" };
}

async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: "GET",
    headers: { "Content-Type": "application/json" },
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}

async function loadBackendPortfolio(userId: string): Promise<BackendPortfolio | null> {
  const portfolios = await fetchJson<BackendPortfolio[]>(`/api/portfolio/${userId}`);
  return portfolios.length > 0 ? portfolios[0] : null;
}

async function loadMarketPrices(tickers: string[]): Promise<Map<string, BackendMarketPrice>> {
  if (tickers.length === 0) {
    return new Map<string, BackendMarketPrice>();
  }

  const params = new URLSearchParams({ tickers: tickers.join(",") });
  const prices = await fetchJson<BackendMarketPrice[]>(`/api/market/prices?${params.toString()}`);
  return new Map(prices.map((p) => [p.ticker, p]));
}

function buildPerformanceSeries(days: number, totalValue: number): PerformanceDataPoint[] {
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

export async function fetchPortfolio(userId = "user-001"): Promise<PortfolioResponseDTO> {
  const backendPortfolio = await loadBackendPortfolio(userId);
  if (!backendPortfolio) {
    return {
      portfolioId: "n/a",
      ownerId: userId,
      name: "Main Portfolio",
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
  const pricesByTicker = await loadMarketPrices(tickers);

  const holdings: AssetHoldingDTO[] = backendPortfolio.holdings.map((h) => {
    const meta = getTickerMeta(h.assetTicker);
    const price = pricesByTicker.get(h.assetTicker);
    const currentPrice = price?.currentPrice ?? 0;
    const totalValue = Number((h.quantity * currentPrice).toFixed(2));

    return {
      id: h.id,
      ticker: h.assetTicker,
      name: meta.name,
      assetClass: meta.assetClass,
      quantity: h.quantity,
      currentPrice,
      totalValue,
      avgCostBasis: currentPrice,
      unrealizedPnL: 0,
      change24hPercent: 0,
      change24hAbsolute: 0,
      portfolioWeight: 0,
      lastUpdatedAt: price?.updatedAt ?? new Date().toISOString(),
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
    name: "Main Portfolio",
    currency: "USD",
    summary,
    holdings: holdingsWithWeight,
    asOfDate: new Date().toISOString(),
  };
}

export async function fetchPortfolioPerformance(
  userId = "user-001",
  days = 30
): Promise<PortfolioPerformanceDTO> {
  const portfolio = await fetchPortfolio(userId);
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

export async function fetchAssetAllocation(userId = "user-001"): Promise<AssetAllocationDTO> {
  const portfolio = await fetchPortfolio(userId);
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
  };

  const labelMap: Record<string, string> = {
    STOCK: "Stocks",
    ETF: "ETFs / Index",
    CRYPTO: "Crypto",
    BOND: "Bonds",
    CASH: "Cash",
    COMMODITY: "Commodities",
  };

  const slices: AllocationSliceDTO[] = Object.entries(byClass).map(([assetClass, value]) => ({
    assetClass: assetClass as AssetClass,
    label: labelMap[assetClass] ?? assetClass,
    value,
    percentage: portfolio.summary.totalValue > 0 ? (value / portfolio.summary.totalValue) * 100 : 0,
    color: colorMap[assetClass] ?? "#888",
  }));

  return {
    portfolioId: portfolio.portfolioId,
    totalValue: portfolio.summary.totalValue,
    slices,
  };
}
