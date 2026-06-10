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

/** Fixture analytics response with two holdings and a 7-day performance series.
 * Uses Task 5 nullable semantics: unrealizedPnL/change fields use real values where
 * available; null is used where a value is genuinely unavailable. */
const analyticsFixture = {
  totalValue: 48250.0,
  totalCostBasis: 44000.0,
  totalUnrealizedPnL: 4250.0,
  totalUnrealizedPnLPercent: 9.6591,
  baseCurrency: "USD",
  partialValuation: false,
  bestPerformer: { ticker: "AAPL", change24hPercent: 5.26 },
  worstPerformer: { ticker: "BTC", change24hPercent: -2.14 },
  holdings: [
    {
      ticker: "AAPL",
      quantity: 10,
      currentPrice: 212.5,
      currentValueBase: 2125.0,
      avgCostBasis: 190.0,
      costBasisCurrency: "USD",
      unrealizedPnL: 225.0,
      unrealizedPnLPercent: 11.84,
      change24hAbsolute: 10.6,
      change24hPercent: 5.26,
      change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
      changeBasis: "WITHIN_24H_WINDOW",
      quoteCurrency: "USD",
      displayAssetClass: "STOCK",
    },
    {
      ticker: "BTC",
      quantity: 0.65,
      currentPrice: 70775.0,
      currentValueBase: 46003.75,
      avgCostBasis: 64000.0,
      costBasisCurrency: "USD",
      unrealizedPnL: 4403.75,
      unrealizedPnLPercent: 10.58,
      change24hAbsolute: -1543.5,
      change24hPercent: -2.14,
      change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
      changeBasis: "WITHIN_24H_WINDOW",
      quoteCurrency: "USD",
      displayAssetClass: "CRYPTO",
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
  performanceCoverage: {
    holdingsWithHistory: 2,
    totalHoldings: 2,
    partial: false,
    synthetic: false,
  },
};

// ── Insight-service fixtures ────────────────────────────────────────────────

/**
 * Realistic market summary fixture with 3 tickers.
 *
 * The list endpoint (GET /api/insights/market-summary) returns price/trend data only —
 * aiSummary is intentionally absent to prevent an unbounded Bedrock fan-out.
 * AI sentiment is only available on the per-ticker endpoint.
 */
export const insightMarketSummaryFixture: Record<
  string,
  {
    ticker: string;
    latestPrice: number;
    priceHistory: number[];
    trendPercent: number | null;
  }
> = {
  AAPL: {
    ticker: "AAPL",
    latestPrice: 178.5,
    priceHistory: [175.0, 176.2, 177.8, 178.5],
    trendPercent: 2.0,
  },
  MSFT: {
    ticker: "MSFT",
    latestPrice: 420.0,
    priceHistory: [422.0, 421.0, 420.0],
    trendPercent: -0.47,
  },
  GOOG: {
    ticker: "GOOG",
    latestPrice: 175.0,
    priceHistory: [175.0],
    trendPercent: null,
  },
};

/** Error handler for POST /api/chat returning 503. Use with server.use() in tests. */
export const chatError503Handler = http.post("/api/chat", () =>
  HttpResponse.json(
    { error: "AI advisor unavailable", retryable: true },
    { status: 503 },
  ),
);

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

    // Parse requested tickers to return an explicit price row for each
    const url = new URL(request.url);
    const tickersParam = url.searchParams.get("tickers") ?? "";
    const requestedTickers = tickersParam
      ? tickersParam.split(",").map((t) => t.trim()).filter(Boolean)
      : [];

    // Fixture prices for known tickers; unknown tickers get explicit unavailable marker
    const knownPrices: Record<string, number> = {
      AAPL: 212.5,
      BTC: 70775.0,
      "BTC-USD": 70775.0,
      TSLA: 245.3,
      MSFT: 420.0,
      GOOGL: 175.0,
    };

    const prices = requestedTickers.map((ticker) => {
      const price = knownPrices[ticker];
      if (price != null) {
        return {
          ticker,
          currentPrice: price,
          observedAt: new Date(Date.now() - 3600_000).toISOString(),
          quoteCurrency: ticker.endsWith("-USD") || ticker === "BTC" ? "USD" : "USD",
          priceUnavailable: false,
        };
      }
      // Explicit unavailable marker — never omit or fabricate $0.00
      return {
        ticker,
        currentPrice: null,
        observedAt: null,
        priceUnavailable: true,
      };
    });

    return HttpResponse.json(prices);
  }),

  // ── Insight-service handlers ──────────────────────────────────────────────

  http.get("/api/insights/market-summary", ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    return HttpResponse.json(insightMarketSummaryFixture);
  }),

  http.get("/api/insights/market-summary/:ticker", ({ request, params }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    const ticker = (params.ticker as string).toUpperCase();
    const summary = insightMarketSummaryFixture[ticker];
    if (!summary || summary.latestPrice === null) {
      return new HttpResponse(null, { status: 404 });
    }
    // Per-ticker endpoint enriches with AI sentiment (Bedrock, Redis-cached 60 min).
    const aiSummaries: Record<string, string> = {
      AAPL: "AAPL is Bullish. Prices are rising steadily.",
      GOOG: "GOOG is Neutral. Low trading volume.",
    };
    return HttpResponse.json({
      ...summary,
      aiSummary: aiSummaries[ticker] ?? null,
    });
  }),

  http.post("/api/chat", async ({ request }) => {
    const unauthorized = requireBearer(request);
    if (unauthorized) return unauthorized;

    const body = (await request.json()) as { message: string; ticker?: string };
    return HttpResponse.json({
      response: `Here's what I know about ${body.ticker ?? "the market"}: prices are trending upward with moderate volume.`,
    });
  }),
];
