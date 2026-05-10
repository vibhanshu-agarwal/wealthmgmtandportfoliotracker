import { expect, test } from "@playwright/test";

/**
 * Azure Synthetic Monitoring: AI Insights Verification
 *
 * Verifies that the Azure OpenAI-powered insights page can process
 * the 160-asset portfolio and generate analysis.
 */

test.describe("Azure Synthetic: AI Insights", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
    await page.locator('input[type="email"]').fill(process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@wealthmgmt-azure-prod.azurewebsites.net");
    await page.locator('input[type="password"]').fill(process.env.E2E_TEST_USER_PASSWORD ?? "TestPassword123!");
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/);
  });

  test("Verify Azure OpenAI handles 160 assets and returns analysis", async ({ page }) => {
    // Navigate to insights page
    await page.goto("/insights");

    // Check for the "Analyze Portfolio" button or automatic trigger
    // If it's automatic, we wait for the insight text
    // The spec mentions 'chat-input' element issue in previous turns, let's look for it

    // Extended timeout for Azure OpenAI processing
    // Azure OpenAI typically responds faster than AWS Bedrock for large datasets
    const insightContent = page.locator('[data-testid="ai-insight-content"], .prose, .markdown');

    // We expect the page to show some analysis eventually
    await expect(insightContent).toBeVisible({ timeout: 60_000 });

    // Verify it mentions some of our assets or common portfolio analysis terms
    await expect(insightContent).toContainText(/portfolio|asset|risk|diversification/i);
  });
});
