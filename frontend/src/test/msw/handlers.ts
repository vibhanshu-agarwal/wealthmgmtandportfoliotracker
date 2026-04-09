import { http, HttpResponse } from "msw";

function requireBearer(request: Request): Response | null {
  const auth = request.headers.get("Authorization");
  if (!auth?.startsWith("Bearer ")) {
    return new HttpResponse(null, { status: 401 });
  }
  return null;
}

const today = new Date().toISOString().split("T")[0];
const daysAgo = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().split("T")[0];
};

/** Fixture analytics response with two holdings and a 7-day performance series. */
const analyticsFixture = {
  totalValue: 48250.0,
  totalCostBasis: 48250.0,
  totalUnrealizedPnL: 0.0,
  totalUnrealizedPnLPercent: 0.0,
  baseCurrency: "USD",
  bestPerformer: { ticker: "AAPL", change24hPercent: 5.26 },
  worstPerformer: { ticker: "BTC", change24hPercent: -2.14 },
  holdings: [
    {
      ticker: "AAPL",
      quantity: 10,
      currentPrice: 212.5,
      currentValueBase: 2125.0,
      avgCostBasis: 212.5,
      unrealizedPnL: 0.0,
      change24hAbsolute: 10.6,
      change24hPercent: 5.26,
      quoteCurrency: "USD",
    },
    {
      ticker: "BTC",
      quantity: 0.65,
      currentPrice: 70775.0,
      currentValueBase: 46003.75,
      avgCostBasis: 70775.0,
      unrealizedPnL: 0.0,
      change24hAbsolute: -1543.5,
      change24hPercent: -2.14,
      quoteCurrency: "USD",
    },
  ],
  performanceSeries: Array.from({ length: 7 }, (_, i) => {
    const value = 44000 + i * 600;
    return {
      date: daysAgo(6 - i),
      value,
      change: i === 0 ? 0 : 600,
    };
  }).concat([{ date: today, value: 48250.0, change: 650.0 }]),
};

export const handlers = [
  http.get("/api/portfolio", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json([
      {
        id: "portfolio-001",
        userId: "user-001",
        createdAt: new Date().toISOString(),
        holdings: [],
      },
    ]);
  }),

  http.get("/api/portfolio/summary", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    const url = new URL(request.url);
    const userId = url.searchParams.get("userId") ?? "user-001";

    return HttpResponse.json({
      userId,
      portfolioCount: 1,
      totalHoldings: 4,
      totalValue: 284531.42,
    });
  }),

  http.get("/api/portfolio/analytics", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json(analyticsFixture);
  }),

  http.get("/api/market/prices", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json([]);
  }),
];
