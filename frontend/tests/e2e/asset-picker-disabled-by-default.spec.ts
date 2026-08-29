/**
 * B2 Checkpoint 4 — verifies the default static export (both B2 flags unset, the same
 * build `playwright.mocked.config.ts` already produces) has no reachable Asset Picker
 * entry point. Runs on the SHARED mocked config's webServer deliberately — this is the
 * one spec whose entire point is confirming that build's default-disabled state, not
 * the picker-enabled build `playwright.asset-picker.mocked.config.ts` produces.
 */
import { expect, test } from "@playwright/test";

const SESSION = {
  token: "e2e-mocked-token",
  userId: "user-001",
  email: "dev@localhost.local",
  name: "Dev User",
};

test.describe("Asset Picker — disabled by default", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((session) => {
      window.localStorage.setItem("wmpt.auth.session", JSON.stringify(session));
    }, SESSION);

    await page.route("**/api/portfolio", (route) =>
      route.fulfill(
        json([
          {
            id: "p1",
            userId: "user-001",
            createdAt: "2026-01-01T00:00:00Z",
            version: 4,
            holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
          },
        ]),
      ),
    );
    await page.route("**/api/portfolio/summary**", (route) =>
      route.fulfill(
        json({
          userId: "user-001",
          portfolioCount: 1,
          totalHoldings: 1,
          totalValue: 1000,
          assetPriceFreshness: {
            state: "FRESH",
            staleHoldings: 0,
            unknownPriceHoldings: 0,
            missingPriceHoldings: 0,
          },
        }),
      ),
    );
    await page.route("**/api/portfolio/analytics", (route) =>
      route.fulfill(
        json({
          totalValue: 1000,
          totalCostBasis: 1000,
          totalUnrealizedPnL: null,
          totalUnrealizedPnLPercent: null,
          baseCurrency: "USD",
          partialValuation: false,
          bestPerformer: { ticker: "AAPL", change24hPercent: null },
          worstPerformer: { ticker: "AAPL", change24hPercent: null },
          holdings: [],
          performanceSeries: [],
          performanceCoverage: {
            holdingsWithHistory: 0,
            totalHoldings: 0,
            partial: false,
            synthetic: false,
          },
        }),
      ),
    );
    await page.route("**/api/market/prices**", (route) => route.fulfill(json([])));
  });

  test("no Edit Holdings button, and /api/assets is never requested", async ({ page }) => {
    let catalogRequested = false;
    await page.route("**/api/assets", (route) => {
      catalogRequested = true;
      return route.fulfill(json({ catalogVersion: "v1", assets: [] }));
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({
      timeout: 15_000,
    });

    await expect(page.getByRole("button", { name: "Edit Holdings" })).toHaveCount(0);
    // Give any accidental fetch a moment to have fired before asserting its absence.
    await page.waitForTimeout(500);
    expect(catalogRequested).toBe(false);
  });
});

function json(body: unknown) {
  return { status: 200, contentType: "application/json", body: JSON.stringify(body) };
}
