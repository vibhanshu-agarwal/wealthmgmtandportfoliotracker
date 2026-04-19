import { expect, test } from "@playwright/test";

/**
 * AWS Synthetic Monitoring Tests
 * 
 * Target: https://vibhanshu-ai-portfolio.dev
 * Constraints:
 * - MUST run serially (--workers=1 in CI or implicitly isolated) to avoid Bedrock/Lambda 429s.
 * - MUST use a dedicated test user so production analytics are not polluted.
 * - Extended timeouts handles Lambda cold starts (~20s).
 */

const TEST_USER_EMAIL = process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
const TEST_USER_PASSWORD = process.env.E2E_TEST_USER_PASSWORD ?? "TestPassword123!";

test.describe.serial("Live AWS Synthetic Monitoring", () => {
  // Use a longer timeout for the entire suite to account for cold starts
  test.setTimeout(60_000);

  test("Health Check: System login and dashboard hydration", async ({ page }) => {
    // 1. Navigate to the live site
    await page.goto("/");

    // 2. Perform live login as the dedicated test user
    // Assuming there's a login redirect or we go to /login directly
    if (page.url().includes("/login") || await page.getByRole("button", { name: /log in/i }).isVisible()) {
      const emailInput = page.locator('input[type="email"]');
      const passInput = page.locator('input[type="password"]');
      
      if (await emailInput.isVisible()) {
        await emailInput.fill(TEST_USER_EMAIL);
        await passInput.fill(TEST_USER_PASSWORD);
        await page.getByRole("button", { name: /sign in|log in/i }).click();
      }
    }

    // Wait for the overview or portfolio page to load (handling potential Lambda cold start)
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, { timeout: 30_000 });
    
    // 3. Verify core data loads
    await page.goto("/portfolio");
    const totalValueEl = page.getByTestId("total-value");
    
    // Explicitly wait up to 30s for the cold start of the portfolio-service
    await expect(totalValueEl).toBeVisible({ timeout: 30_000 });
    
    // Ensure the total value isn't an empty placeholder
    const value = await totalValueEl.textContent();
    expect(value).not.toBe("");
  });
  
  test("Health Check: CloudFront/Bedrock AI Insights latency check", async ({ page }) => {
    await page.goto("/ai-insights");
    
    // Check if the page loaded
    await expect(page.getByRole("heading", { name: /AI Insights/i })).toBeVisible({ timeout: 20_000 });
    
    // Note: We avoid heavy interaction with Bedrock here unless strictly necessary 
    // to preserve AWS quotas. We can just verify the service is reachable.
  });
});
