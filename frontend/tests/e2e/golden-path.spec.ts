/**
 * Golden Path E2E Tests
 *
 * Validates the core user journey against the live local stack.
 * Requires:
 *   - Next.js server running on http://127.0.0.1:3000
 *   - Spring Boot API Gateway running on http://localhost:8080
 *   - Flyway V3 seed migration applied (seeds user-001 with AAPL, TSLA, BTC)
 *
 * Run:
 *   npx playwright test tests/e2e/golden-path.spec.ts --reporter=list
 */

import { expect, test } from "@playwright/test";
import { injectAuthSession } from "./helpers/auth";
import { ensurePortfolioWithHoldings, mintJwt } from "./helpers/api";

// ── Suite 1: Data Creation ────────────────────────────────────────────────────

test.describe("Golden Path — Data Creation", () => {
  test.use({ storageState: undefined });

  test.beforeAll(async ({ request }) => {
    // Verify the Flyway V3 seed is in place — AAPL, TSLA, BTC for user-001.
    await ensurePortfolioWithHoldings(request, mintJwt());
  });

  test("portfolio holdings are persisted and returned by the API", async ({ page }) => {
    // Real UI login — NextAuth sets its own CSRF tokens and HttpOnly session cookie.
    await injectAuthSession(page);
    await page.goto("/portfolio");

    await expect(page.getByText("AAPL").first()).toBeVisible();
    await expect(page.getByText("BTC").first()).toBeVisible();
  });
});

// ── Suite 2: Analytics Validation ────────────────────────────────────────────

test.describe("Golden Path — Analytics Validation", () => {
  test.use({ storageState: undefined });

  test.beforeAll(async ({ request }) => {
    await ensurePortfolioWithHoldings(request, mintJwt());
  });

  test("total-value is not $0.00 after holdings are seeded", async ({ page }) => {
    await injectAuthSession(page);
    await page.goto("/portfolio");

    // Force a hard reload so the server reads the session cookie via auth()
    // and hydrates SessionProvider, ensuring useSession() returns "authenticated".
    await page.reload({ waitUntil: "networkidle" });

    await expect(page.getByTestId("total-value")).not.toHaveText("$0.00", {
      timeout: 30_000,
    });
  });

  test("holdings table contains AAPL and BTC tickers", async ({ page }) => {
    await injectAuthSession(page);
    await page.goto("/portfolio");

    await expect(page.getByText("AAPL").first()).toBeVisible();
    await expect(page.getByText("BTC").first()).toBeVisible();
  });
});
