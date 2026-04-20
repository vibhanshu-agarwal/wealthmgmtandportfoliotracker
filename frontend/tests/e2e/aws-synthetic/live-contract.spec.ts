import { expect, test } from "@playwright/test";

/**
 * AWS Synthetic Monitoring: Live Contract Verification
 * 
 * Verifies that the live site correctly hydrates and renders the 160-asset
 * "Golden State" portfolio.
 */

test.describe("AWS Synthetic: Live Contract", () => {
  test.beforeEach(async ({ page }) => {
    // Authenticate by going to login (session is handled by setup project)
    // Or if running standalone, we expect the global-setup to have seeded data.
    await page.goto("/login");
    const emailInput = page.locator('input[type="email"]');
    await emailInput.waitFor({ state: "visible", timeout: 15_000 });
    
    await emailInput.fill(process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev");
    await page.locator('input[type="password"]').fill(process.env.E2E_TEST_USER_PASSWORD ?? "TestPassword123!");
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/);
  });

  test("Verify 160-asset portfolio hydration", async ({ page }) => {
    await page.goto("/portfolio");

    // Wait for the table to load (skeleton to disappear)
    const holdingsTable = page.locator("table");
    await holdingsTable.waitFor({ state: "visible", timeout: 30_000 });

    // Assert total count of assets rendered in the table header description
    // Example: "160 of 160 assets"
    const assetCountDescription = page.locator("p.text-sm.text-muted-foreground", { hasText: /assets/i });
    await expect(assetCountDescription).toContainText("160", { timeout: 10_000 });

    // Verify a sample of tickers from each asset class
    const sampleTickers = [
      "AAPL", "NVDA", "WMT", // US Equity
      "RELIANCE.NS", "TCS.NS", "TATAMOTORS.NS", // NSE
      "BTC-USD", "ETH-USD", "SOL-USD", // Crypto
      "EURUSD=X", "USDINR=X" // Forex
    ];

    for (const ticker of sampleTickers) {
      await expect(page.locator("table")).toContainText(ticker);
    }

    // Verify row count (excluding header)
    const rows = page.locator("table tbody tr");
    await expect(rows).toHaveCount(160, { timeout: 10_000 });
  });

  test("UI scaling check for 160 rows", async ({ page }) => {
    await page.goto("/portfolio");
    
    // Scroll to the bottom to ensure no virtualization issues or layout breakage
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    
    // Check if footer totals are visible and non-zero
    const footerValue = page.locator("div.text-right >> p.font-bold").first();
    await expect(footerValue).not.toHaveText("$0.00");
  });
});
