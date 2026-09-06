/**
 * B2 Checkpoint 4 — one mocked-backed happy path and one conflict path through the
 * Asset Picker. Uses a local static export plus page/network mocks (no backend health
 * dependency) — run against `playwright.asset-picker.mocked.config.ts`, the one build
 * this repo produces with `NEXT_PUBLIC_ENABLE_ASSET_PICKER=true`.
 *
 *   npx playwright test --config playwright.asset-picker.mocked.config.ts
 */
import { expect, test, type Page } from "@playwright/test";

const SESSION = {
  token: "e2e-mocked-token",
  userId: "user-001",
  email: "dev@localhost.local",
  name: "Dev User",
};

const CATALOG = {
  catalogVersion: "v1",
  assets: [
    { ticker: "AAPL", name: "Apple Inc.", aliases: ["Apple"], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
    { ticker: "GOOGL", name: "Alphabet", aliases: ["Google"], assetClass: "STOCK", quoteCurrency: "USD", lifecycleStatus: "ACTIVE" },
  ],
};

function json(body: unknown, status = 200) {
  return { status, contentType: "application/json", body: JSON.stringify(body) };
}

function portfolioBody(version: number) {
  return [
    {
      id: "p1",
      userId: "user-001",
      createdAt: "2026-01-01T00:00:00Z",
      version,
      holdings: [{ id: "h1", assetTicker: "AAPL", quantity: "10" }],
    },
  ];
}

function summaryBody() {
  return {
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
  };
}

function analyticsBody() {
  return {
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
    performanceCoverage: { holdingsWithHistory: 0, totalHoldings: 0, partial: false, synthetic: false },
  };
}

async function mockReadOnlyEndpoints(page: Page, portfolioVersion: number) {
  await page.route("**/api/portfolio", (route) => route.fulfill(json(portfolioBody(portfolioVersion))));
  await page.route("**/api/portfolio/summary**", (route) => route.fulfill(json(summaryBody())));
  await page.route("**/api/portfolio/analytics", (route) => route.fulfill(json(analyticsBody())));
  await page.route("**/api/market/prices**", (route) => route.fulfill(json([])));
  await page.route("**/api/assets", (route) => route.fulfill(json(CATALOG)));
  await page.route("**/api/presence/demo", (route) =>
    route.fulfill(json({ anotherSessionActive: false })),
  );
}

test.describe("Asset Picker — mocked flows", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((session) => {
      window.localStorage.setItem("wmpt.auth.session", JSON.stringify(session));
    }, SESSION);
  });

  test("happy path: browse, add a ticker, review, save", async ({ page }) => {
    await mockReadOnlyEndpoints(page, 7);
    let putBody: unknown = null;
    await page.route("**/api/portfolio/holdings", (route) => {
      putBody = route.request().postDataJSON();
      return route.fulfill(
        json({
          id: "p1",
          userId: "user-001",
          createdAt: "2026-01-01T00:00:00Z",
          version: 8,
          holdings: [
            { id: "h1", assetTicker: "AAPL", quantity: "10" },
            { id: "h2", assetTicker: "GOOGL", quantity: "3" },
          ],
        }),
      );
    });

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Portfolio" })).toBeVisible({ timeout: 15_000 });

    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await expect(dialog).toBeVisible();

    // AAPL is already checked (GC.1 — the draft opens fully seeded).
    await expect(dialog.getByRole("checkbox", { name: "Select AAPL" })).toHaveAttribute(
      "aria-checked",
      "true",
    );

    // Add GOOGL and give it a quantity.
    await dialog.getByRole("checkbox", { name: "Select GOOGL" }).click();
    await dialog.getByRole("textbox", { name: "GOOGL quantity" }).fill("3");

    await dialog.getByRole("button", { name: /review changes/i }).click();
    await expect(dialog.getByText(/added.*1/i)).toBeVisible();
    await expect(dialog.getByText("GOOGL")).toBeVisible();

    await dialog.getByRole("button", { name: /save changes/i }).click();

    // Success: modal closes, and the button-area announces the save.
    await expect(dialog).not.toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("status")).toHaveText(/saved/i);

    expect(putBody).toEqual({
      expectedVersion: 7,
      holdings: [
        { ticker: "AAPL", quantity: "10" },
        { ticker: "GOOGL", quantity: "3" },
      ],
    });
  });

  test("conflict path: 409 freezes the draft; reload-and-start-over recovers", async ({ page }) => {
    await mockReadOnlyEndpoints(page, 7);
    await page.route("**/api/portfolio/holdings", (route) =>
      route.fulfill(
        json(
          {
            error: "portfolio_version_conflict",
            message: "Someone else saved a different version.",
            currentVersion: 9,
          },
          409,
        ),
      ),
    );

    await page.goto("/portfolio", { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "Edit Holdings" }).click();
    const dialog = page.getByRole("dialog", { name: "Edit Holdings" });
    await expect(dialog).toBeVisible();

    await dialog.getByRole("button", { name: /review changes/i }).click();
    await dialog.getByRole("button", { name: /save changes/i }).click();

    // GC.4: frozen conflict state — the draft stays visible, read-only.
    await expect(dialog.getByText(/someone else saved a different version/i)).toBeVisible();
    const region = dialog.getByRole("region", { name: /draft/i });
    await expect(region).toBeVisible();
    await expect(region).toContainText("AAPL");
    await expect(dialog.getByRole("checkbox")).toHaveCount(0);

    // The next open re-reads the portfolio at its now-current version.
    await mockReadOnlyEndpoints(page, 9);

    await dialog.getByRole("button", { name: /reload latest.*start over/i }).click();
    await expect(dialog).not.toBeVisible({ timeout: 10_000 });
  });
});
