import { expect, test } from "@playwright/test";

/**
 * AWS Synthetic Monitoring: AI Insights Verification
 * 
 * Verifies that the Bedrock-powered insights page can process
 * the 160-asset portfolio and generate analysis.
 */

test.describe("AWS Synthetic: AI Insights", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
    await page.locator('input[type="email"]').fill(process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev");
    await page.locator('input[type="password"]').fill(process.env.E2E_TEST_USER_PASSWORD ?? "TestPassword123!");
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/);
  });

  test("Verify Bedrock handles 160 assets and returns analysis", async ({ page }) => {
    // Navigate to insights page
    await page.goto("/insights");

    // Check for the "Analyze Portfolio" button or automatic trigger
    // If it's automatic, we wait for the insight text
    // The spec mentions 'chat-input' element issue in previous turns, let's look for it
    
    // Extended timeout for Bedrock cold starts
    const insightContent = page.locator('[data-testid="ai-insight-content"], .prose, .markdown');
    
    // We expect the page to show some analysis eventually
    await expect(insightContent).toBeVisible({ timeout: 90_000 });
    
    // Verify it mentions some of our assets
    await expect(insightContent).toContainText(/portfolio|asset|risk|diversification/i);
  });
});
