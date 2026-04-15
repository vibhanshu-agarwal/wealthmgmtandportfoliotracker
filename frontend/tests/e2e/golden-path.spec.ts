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
import { ensurePortfolioWithHoldings } from "./helpers/api";

// ── Suite 1: Data Creation ────────────────────────────────────────────────────

test.describe("Golden Path — Data Creation", () => {
  test.beforeEach(async ({ request }) => {
    await ensurePortfolioWithHoldings(request);
  });

  test("portfolio holdings are persisted and returned by the API", async ({ page }) => {
    await page.goto("/portfolio");

    await expect(page.getByText("AAPL").first()).toBeVisible();
    await expect(page.getByText("BTC").first()).toBeVisible();
  });
});

// ── Suite 2: Analytics Validation ────────────────────────────────────────────

test.describe("Golden Path — Analytics Validation", () => {
  test.beforeEach(async ({ request }) => {
    await ensurePortfolioWithHoldings(request);
  });

  test("total-value is not $0.00 after holdings are seeded", async ({ page }) => {
    await page.goto("/portfolio");

    await expect(page.getByTestId("total-value")).not.toHaveText("$0.00", {
      timeout: 30_000,
    });
  });

  test("holdings table contains AAPL and BTC tickers", async ({ page }) => {
    await page.goto("/portfolio");

    await expect(page.getByText("AAPL").first()).toBeVisible();
    await expect(page.getByText("BTC").first()).toBeVisible();
  });
});
