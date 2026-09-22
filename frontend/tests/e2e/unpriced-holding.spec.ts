/**
 * Unpriced-holding regression (fully mocked — no backend required). Finding F1.
 *
 * A holding with no market_prices row reaches the browser as:
 *   - GET /api/portfolio/analytics → currentPrice, currentValueBase and quoteCurrency null
 *   - GET /api/market/prices       → the ticker is absent from the response
 *   - GET /api/insights/market-summary may also carry a ticker whose latestPrice is null
 *
 * Before the fix, the header ticker (rendered on every dashboard page) called
 * `null.toFixed(2)` and every page showed Next's "This page couldn't load" error.
 * Every dashboard page must stay usable, and the unpriced holding must read as
 * unavailable ("—"), never as an invented $0.00.
 *
 * Runs stack-less via playwright.mocked.config.ts, and in CI through the main
 * config's chromium project (mocks take precedence over the network either way).
 *
 * The "no $0.00" assertions are sound only under these mocks: real market data has
 * sub-cent prices (e.g. SHIB-USD) that formatCurrency rounds to "$0.00" (finding F10),
 * so do not lift them into a real-data suite as they stand.
 */

import { expect, test, type Page } from "@playwright/test";

const SESSION = {
  token: "e2e-mocked-token",
  userId: "user-001",
  email: "dev@localhost.local",
  name: "Dev User",
};

const portfolioBody = [
  {
    id: "7d1f0f3e-9a55-4a0b-8f3e-5b3c1f1a0001",
    userId: "user-001",
    version: 3,
    createdAt: "2026-09-22T06:00:00Z",
    holdings: [
      { id: "7d1f0f3e-9a55-4a0b-8f3e-5b3c1f1a0002", assetTicker: "AAPL", quantity: "12" },
      { id: "7d1f0f3e-9a55-4a0b-8f3e-5b3c1f1a0003", assetTicker: "SOL-USD", quantity: "40" },
    ],
  },
];

const summaryBody = {
  userId: "user-001",
  portfolioCount: 1,
  totalHoldings: 2,
  totalValue: 2550.0,
  baseCurrency: "USD",
  partialValuation: false,
};

const holdingBase = {
  avgCostBasis: null,
  costBasisCurrency: null,
  unrealizedPnL: null,
  unrealizedPnLPercent: null,
  change24hReferenceAt: null,
  changeBasis: null,
};

const analyticsBody = {
  totalValue: 2550.0,
  totalCostBasis: 0,
  totalUnrealizedPnL: null,
  totalUnrealizedPnLPercent: null,
  baseCurrency: "USD",
  partialValuation: false,
  bestPerformer: { ticker: "AAPL", change24hPercent: 1.2 },
  worstPerformer: { ticker: "AAPL", change24hPercent: 1.2 },
  holdings: [
    {
      ...holdingBase,
      ticker: "AAPL",
      quantity: 12,
      currentPrice: 212.5,
      currentValueBase: 2550.0,
      change24hAbsolute: 2.52,
      change24hPercent: 1.2,
      quoteCurrency: "USD",
      displayAssetClass: "STOCK",
    },
    {
      ...holdingBase,
      ticker: "SOL-USD",
      quantity: 40,
      currentPrice: null,
      currentValueBase: null,
      change24hAbsolute: null,
      change24hPercent: null,
      quoteCurrency: null,
      displayAssetClass: "CRYPTO",
    },
  ],
  performanceSeries: [],
  performanceCoverage: { holdingsWithHistory: 0, totalHoldings: 2, partial: true, synthetic: false },
};

const pricesBody = [
  {
    ticker: "AAPL",
    currentPrice: 212.5,
    quoteCurrency: "USD",
    observedAt: "2026-09-22T06:00:00Z",
    previousReferencePrice: 210.0,
    previousReferenceAt: "2026-09-21T06:00:00Z",
    changeAbsolute: 2.5,
    changePercent: 1.19,
    changeBasis: "WITHIN_24H_WINDOW",
  },
];

const marketSummaryBody = {
  AAPL: { ticker: "AAPL", latestPrice: 212.5, priceHistory: [210.0, 212.5], trendPercent: 1.19, aiSummary: null },
  NVDA: { ticker: "NVDA", latestPrice: null, priceHistory: [], trendPercent: null, aiSummary: null },
};

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

function rowFor(page: Page, ticker: string) {
  return page.getByRole("row").filter({ has: page.getByText(ticker, { exact: true }) });
}

test.describe("Unpriced holding — every dashboard page stays usable (F1)", () => {
  const pageErrors: string[] = [];

  test.beforeEach(async ({ page }) => {
    pageErrors.length = 0;
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.addInitScript((session) => {
      window.localStorage.setItem("wmpt.auth.session", JSON.stringify(session));
    }, SESSION);
    await page.route("**/api/portfolio", (route) => route.fulfill(json(portfolioBody)));
    await page.route("**/api/portfolio/summary**", (route) => route.fulfill(json(summaryBody)));
    await page.route("**/api/portfolio/analytics", (route) => route.fulfill(json(analyticsBody)));
    await page.route("**/api/market/prices**", (route) => route.fulfill(json(pricesBody)));
    await page.route("**/api/insights/market-summary**", (route) => route.fulfill(json(marketSummaryBody)));
  });

  test.afterEach(async ({ page }) => {
    expect(pageErrors, "uncaught page errors").toEqual([]);
    await expect(page.getByText("This page couldn't load")).toHaveCount(0);
  });

  test("Overview: totals render and the header ticker omits the unpriced holding", async ({ page }) => {
    await page.goto("/overview", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("total-value")).toHaveText("$2,550.00", { timeout: 15_000 });
    // Allocation: the unpriced SOL-USD is the only crypto holding, so no empty Crypto slice.
    const legend = page.getByRole("main").locator("li");
    await expect(legend.filter({ hasText: "Stocks" })).toHaveCount(1, { timeout: 15_000 });
    await expect(legend.filter({ hasText: "Crypto" })).toHaveCount(0);
    const ticker = page.getByLabel("Market ticker");
    await expect(ticker.getByText("AAPL").first()).toBeVisible();
    await expect(ticker.getByText("SOL-USD")).toHaveCount(0);
    await expect(ticker.getByText("$0.00")).toHaveCount(0);
  });

  test("Portfolio: the unpriced holding's price and value read as unavailable", async ({ page }) => {
    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio", level: 1 })).toBeVisible({ timeout: 15_000 });
    const unpriced = rowFor(page, "SOL-USD");
    await expect(unpriced).toHaveCount(1, { timeout: 15_000 });
    const cells = unpriced.getByRole("cell");
    await expect(cells.nth(2)).toHaveText("—");
    await expect(cells.nth(3)).toHaveText("—");
    await expect(unpriced.getByText("$0.00")).toHaveCount(0);
    await expect(rowFor(page, "AAPL").getByRole("cell").nth(2)).toHaveText("$212.50");
  });

  test("Market Data: the unpriced holding's price reads as unavailable", async ({ page }) => {
    await page.goto("/market-data", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Market Data", level: 1 })).toBeVisible({ timeout: 15_000 });
    const unpriced = rowFor(page, "SOL-USD");
    await expect(unpriced).toHaveCount(1, { timeout: 15_000 });
    await expect(unpriced.getByRole("cell").nth(1)).toHaveText("—");
    await expect(unpriced.getByText("$0.00")).toHaveCount(0);
    await expect(rowFor(page, "AAPL").getByRole("cell").nth(1)).toHaveText("$212.50");
  });

  test("AI Insights: cards render, and a ticker without a latest price reads as unavailable", async ({ page }) => {
    await page.goto("/ai-insights", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "AI Insights", level: 1 })).toBeVisible({ timeout: 15_000 });
    const main = page.getByRole("main");
    await expect(main.getByText("$212.50").first()).toBeVisible({ timeout: 15_000 });
    await expect(main.getByText("NVDA", { exact: true })).toBeVisible();
    await expect(main.getByText("$0.00")).toHaveCount(0);
  });
});
