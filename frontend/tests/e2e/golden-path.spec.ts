/**
 * Golden Path E2E Tests
 *
 * Validates the core user journey against the live local stack.
 * Authentication is handled by the global setup (storageState) —
 * all tests start pre-authenticated.
 *
 * Requires:
 *   - Next.js server running on http://localhost:3000
 *   - Spring Boot API Gateway running on http://127.0.0.1:8080
 *   - Flyway V3 seed migration applied (seeds user-001 with AAPL, TSLA, BTC)
 *
 * Run:
 *   npx playwright test tests/e2e/golden-path.spec.ts --reporter=list
 */

import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { ensurePortfolioWithHoldings } from "./helpers/api";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

/**
 * Navigates to /portfolio and waits for the holdings table to render the given
 * tickers.
 *
 * Regression guard: the static server must serve portfolio.html for /portfolio.
 * `serve -s` (SPA mode) used to rewrite EVERY route to index.html, whose
 * embedded NEXT_REDIRECT payload silently replaced the URL with /overview.
 * The test then asserted against the Overview page, where the only "AAPL" text
 * comes from the PortfolioTicker strip — which hides itself whenever the
 * portfolio-analytics cache is stale-empty (30 s TTL) — causing the flake.
 */
async function gotoPortfolioAndWaitForTickers(
  page: Page,
  tickers: string[],
): Promise<void> {
  await page.goto("/portfolio", { waitUntil: "domcontentloaded" });

  // Fails loudly if the static server SPA-fallback regression returns:
  // the /portfolio document must be the Portfolio page, not a redirect shell.
  await expect(
    page.getByRole("heading", { name: "Portfolio" }),
    "Expected the Portfolio page heading — if this fails, /portfolio was " +
      "likely served index.html (SPA fallback) and redirected to /overview.",
  ).toBeVisible({ timeout: 30_000 });
  await expect(page).toHaveURL(/\/portfolio(\?|#|$)/);

  // Assert tickers inside the holdings table specifically — not the layout's
  // ticker strip — so this validates persisted holdings, not market analytics.
  const holdingsTable = page.getByRole("table");
  for (const ticker of tickers) {
    await expect(holdingsTable.getByText(ticker).first()).toBeVisible({
      timeout: 30_000,
    });
  }
}

// ── Suite 1: Data Creation ────────────────────────────────────────────────────

test.describe("Golden Path — Data Creation", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);
  });

  test("portfolio holdings are persisted and returned by the API", async ({ page }) => {
    await gotoPortfolioAndWaitForTickers(page, ["AAPL", "BTC"]);
  });
});

// ── Suite 2: Analytics Validation ────────────────────────────────────────────

test.describe("Golden Path — Analytics Validation", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);
  });

  test("total-value is not $0.00 after holdings are seeded", async ({ page }) => {
    await page.goto("/portfolio");

    await expect(page.getByTestId("total-value")).not.toHaveText("$0.00", {
      timeout: 30_000,
    });
  });

  test("holdings table contains AAPL and BTC tickers", async ({ page }) => {
    await gotoPortfolioAndWaitForTickers(page, ["AAPL", "BTC"]);
  });
});
