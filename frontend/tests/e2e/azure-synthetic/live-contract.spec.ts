import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * Azure Synthetic Monitoring: Live Contract Verification
 *
 * Verifies that the live site correctly hydrates and renders the full active-catalog
 * "Golden State" portfolio on Azure Container Apps.
 */

// Derived from the Canonical_Manifest, never written as a literal.
//
// Spec A's D6 makes fixed catalog counts a non-invariant: the catalog may legitimately grow or
// shrink, and this monitor must track it rather than pin it. A literal `160` breaks the day the
// catalog changes; a literal lower bound is no better, because it stops detecting a partial
// hydration that still clears the floor. Both were present in this file and both are removed.
//
// `lifecycleStatus` does not exist in the manifest until Spec A lands, so an entry without one is
// ACTIVE by construction — that default is what makes this expression correct before and after.
const MANIFEST = JSON.parse(
  readFileSync(resolve(__dirname, "../../../../config/seed-tickers.json"), "utf-8"),
) as Array<{ ticker: string; assetClass: string; lifecycleStatus?: string }>;

const ACTIVE_ASSETS = MANIFEST.filter(
  (t) => (t.lifecycleStatus ?? "ACTIVE") === "ACTIVE",
);
const EXPECTED_HOLDINGS = ACTIVE_ASSETS.length;

/** One ACTIVE ticker per asset class — sampled from the manifest, never hard-coded. */
const SAMPLE_TICKERS = [...new Set(ACTIVE_ASSETS.map((t) => t.assetClass))]
  .map((cls) => ACTIVE_ASSETS.find((t) => t.assetClass === cls)!.ticker);

test.describe("Azure Synthetic: Live Contract", () => {
  test.beforeEach(async ({ page }) => {
    // Authenticate by going to login (session is handled by setup project)
    // Or if running standalone, we expect the global-setup to have seeded data.
    await page.goto("/login");
    const emailInput = page.locator('input[type="email"]');
    await emailInput.waitFor({ state: "visible", timeout: 15_000 });

    await emailInput.fill(
      process.env.E2E_TEST_USER_EMAIL ??
        "e2e-test-user@vibhanshu-ai-portfolio.dev",
    );
    await page
      .locator('input[type="password"]')
      .fill(process.env.E2E_TEST_USER_PASSWORD!);
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    // 30s matches the Azure login budget used by login.spec.ts and azure-synthetic.spec.ts.
    // The default 5s is not enough: a Container Apps cold start can take the auth round trip
    // past it, and a run on 2026-08-16 failed here at 4.7s with the dashboard already rendered.
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, { timeout: 30_000 });
  });

  test("Verify full active-catalog portfolio hydration", async ({ page }) => {
    await page.goto("/portfolio");

    // Wait for the table to load (skeleton to disappear)
    // Azure Container Apps typically respond faster than Lambda
    const holdingsTable = page.locator("table");
    await holdingsTable.waitFor({ state: "visible", timeout: 30_000 });

    // Assert the holdings actually rendered, by counting table rows.
    //
    // This previously matched a `p.text-sm.text-muted-foreground` containing "160 ... assets".
    // No such element exists: the portfolio page's only element with those classes is the header
    // paragraph "Real-time overview of your holdings and performance." The assertion had been
    // failing on every scheduled run while the page rendered correctly the whole time — which the
    // sibling UI-scaling test, passing in the same file, demonstrates.
    //
    // Counting rows is also class-independent, so a Tailwind change cannot silently break it.
    const holdingRows = page.locator("table tbody tr");
    await expect
      .poll(() => holdingRows.count(), { timeout: 30_000 })
      .toBe(EXPECTED_HOLDINGS);

    // One ACTIVE ticker per asset class, sampled from the manifest.
    //
    // The previous hard-coded list required TATAMOTORS.NS, which Spec A deprecates via
    // Corporate_Action_Migration — so this monitor would have started failing the moment that
    // repair shipped, reporting a product regression where the product had done exactly what its
    // spec required.
    for (const ticker of SAMPLE_TICKERS) {
      await expect(page.locator("table")).toContainText(ticker);
    }

    // Verify row count (excluding header) against the derived cardinality, not a literal.
    const rows = page.locator("table tbody tr");
    await expect(rows).toHaveCount(EXPECTED_HOLDINGS, { timeout: 10_000 });
  });

  test("UI scaling check for the full active catalog", async ({ page }) => {
    await page.goto("/portfolio");

    // Scroll to the bottom to ensure no virtualization issues or layout breakage
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));

    // Check if footer totals are visible and non-zero
    const footerValue = page.locator("div.text-right >> p.font-bold").first();
    await expect(footerValue).not.toHaveText("$0.00");
  });
});
