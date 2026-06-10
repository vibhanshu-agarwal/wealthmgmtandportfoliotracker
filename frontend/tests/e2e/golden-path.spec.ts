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
 * tickers. Recovers from first-navigation races (static-export hydration +
 * client-side localStorage auth + cold server) by reloading once if the
 * holdings have not appeared in the first window.
 *
 * This is needed because the very first navigation of a run can momentarily
 * render the auth skeleton / pre-hydration state before the localStorage
 * session is read, leaving the data subtree unsettled. A reload always lands
 * on the warmed, authenticated state (which is why later identical tests pass).
 */
async function gotoPortfolioAndWaitForTickers(
  page: Page,
  tickers: string[],
): Promise<void> {
  await page.goto("/portfolio", { waitUntil: "domcontentloaded" });

  const firstTicker = page.getByText(tickers[0]).first();
  try {
    await firstTicker.waitFor({ state: "visible", timeout: 15_000 });
  } catch {
    // First navigation didn't settle (hydration / auth / cold-start race) —
    // reload once to land on the warmed authenticated state.
    await page.reload({ waitUntil: "domcontentloaded" });
  }

  for (const ticker of tickers) {
    await expect(page.getByText(ticker).first()).toBeVisible({ timeout: 30_000 });
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
