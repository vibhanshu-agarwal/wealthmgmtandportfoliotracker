/**
 * Portfolio deep-link regression test (fully mocked — no backend required).
 *
 * Reproduces the exact backend state that made the golden-path test flake in CI
 * (run 27280782387, PR #61):
 *   - GET /api/portfolio           → 200 with AAPL + BTC holdings
 *   - GET /api/portfolio/summary   → 200 with non-zero totalValue
 *   - GET /api/portfolio/analytics → 200 but EMPTY (stale 30 s Caffeine cache
 *     entry computed before the holdings were seeded)
 *   - GET /api/market/prices       → 200 with real prices
 *   - GET /api/insights/market-summary → 500 (insight-service Redis down)
 *
 * Two invariants are asserted:
 *   1. A hard navigation to /portfolio must serve the Portfolio page itself.
 *      (`serve -s` SPA mode used to rewrite it to index.html, whose
 *      NEXT_REDIRECT payload silently swapped the URL to /overview.)
 *   2. The holdings table must render persisted holdings from /api/portfolio
 *      alone — independent of analytics-cache freshness or insight health.
 */

import { expect, test } from "@playwright/test";

const SESSION = {
  token: "e2e-mocked-token",
  userId: "user-001",
  email: "dev@localhost.local",
  name: "Dev User",
};

const portfolioBody = [
  {
    id: "f6f9421f-bf70-4207-b049-6cfec7c5cadd",
    userId: "user-001",
    createdAt: "2026-06-10T14:06:45.488556Z",
    holdings: [
      { id: "d1c9bad6-fca4-4e72-8e4d-8bb5c74c6d6f", assetTicker: "AAPL", quantity: 12.0 },
      { id: "10b85ebb-b6ac-491d-84f2-4398b6d4c600", assetTicker: "BTC", quantity: 0.75 },
    ],
  },
];

const summaryBody = {
  userId: "user-001",
  portfolioCount: 1,
  totalHoldings: 2,
  totalValue: 55449.3252,
  baseCurrency: "USD",
  partialValuation: false,
};

// Exact shape of PortfolioAnalyticsService.emptyAnalytics(baseCurrency) —
// what the gateway serves while the per-user analytics cache is stale-empty.
const staleEmptyAnalyticsBody = {
  totalValue: 0,
  totalCostBasis: 0,
  totalUnrealizedPnL: null,
  totalUnrealizedPnLPercent: null,
  baseCurrency: "USD",
  partialValuation: false,
  bestPerformer: { ticker: "N/A", change24hPercent: null },
  worstPerformer: { ticker: "N/A", change24hPercent: null },
  holdings: [],
  performanceSeries: [],
  performanceCoverage: {
    holdingsWithHistory: 0,
    totalHoldings: 0,
    partial: false,
    synthetic: false,
  },
};

const pricesBody = [
  {
    ticker: "AAPL",
    currentPrice: 197.3396,
    quoteCurrency: "USD",
    observedAt: "2026-06-10T14:06:37.156Z",
    previousReferencePrice: null,
    previousReferenceAt: null,
    changeAbsolute: null,
    changePercent: null,
    changeBasis: null,
  },
  {
    ticker: "BTC",
    currentPrice: 70775.0,
    quoteCurrency: "USD",
    observedAt: "2026-06-10T14:06:37.156Z",
    previousReferencePrice: null,
    previousReferenceAt: null,
    changeAbsolute: null,
    changePercent: null,
    changeBasis: null,
  },
];

test.describe("Portfolio deep link — stale analytics resilience", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((session) => {
      window.localStorage.setItem("wmpt.auth.session", JSON.stringify(session));
    }, SESSION);

    const json = (body: unknown) => ({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    await page.route("**/api/portfolio", (route) => route.fulfill(json(portfolioBody)));
    await page.route("**/api/portfolio/summary**", (route) => route.fulfill(json(summaryBody)));
    await page.route("**/api/portfolio/analytics", (route) =>
      route.fulfill(json(staleEmptyAnalyticsBody)),
    );
    await page.route("**/api/market/prices**", (route) => route.fulfill(json(pricesBody)));
    await page.route("**/api/insights/market-summary**", (route) =>
      route.fulfill({ status: 500, contentType: "application/json", body: "{}" }),
    );
  });

  test("holdings table renders AAPL/BTC despite stale-empty analytics and insight 500", async ({
    page,
  }) => {
    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });

    // Invariant 1: /portfolio serves the Portfolio page (no SPA-fallback redirect).
    await expect(
      page.getByRole("heading", { name: "Portfolio" }),
      "Expected the Portfolio page heading — if this fails, /portfolio was " +
        "likely served index.html (SPA fallback) and redirected to /overview.",
    ).toBeVisible({ timeout: 15_000 });
    await expect(page).toHaveURL(/\/portfolio(\?|#|$)/);

    // Invariant 2: holdings render from /api/portfolio alone.
    const holdingsTable = page.getByRole("table");
    await expect(holdingsTable.getByText("AAPL").first()).toBeVisible({ timeout: 15_000 });
    await expect(holdingsTable.getByText("BTC").first()).toBeVisible({ timeout: 15_000 });

    // Summary card uses /api/portfolio/summary — must not be $0.00.
    await expect(page.getByTestId("total-value")).not.toHaveText("$0.00");
  });
});
