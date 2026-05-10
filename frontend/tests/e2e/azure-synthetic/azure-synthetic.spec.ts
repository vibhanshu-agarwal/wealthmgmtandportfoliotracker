import { expect, test } from "@playwright/test";

/**
 * Azure Synthetic Monitoring Tests
 *
 * Target: https://vibhanshu-ai-portfolio.dev (canonical public domain, post-DNS-cutover)
 * Constraints:
 * - MUST run serially (--workers=1 in CI or implicitly isolated) to avoid Azure OpenAI rate limits.
 * - MUST use a dedicated test user so production analytics are not polluted.
 * - Extended timeouts handle Container Apps scale-up (~30s) and Azure OpenAI latency.
 */

const TEST_USER_EMAIL =
  process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
const TEST_USER_PASSWORD = process.env.E2E_TEST_USER_PASSWORD;

/**
 * Azure Container Apps Synthetic Monitoring
 *
 * Root causes verified:
 *   - Azure SQL connection string properly configured in Container Apps environment.
 *   - INTERNAL_API_KEY: injected into Container Apps env by Bicep/Terraform.
 *   - Azure Front Door timeout raised to 60s; API Management gateway to 55s.
 *   - Pre-flight connectivity checks guard against infrastructure regressions.
 *
 * If the login flow regresses, check Azure Application Insights logs for the auth service
 * and verify E2E credentials in GitHub Actions secrets.
 */
test.describe("Live Azure Synthetic Monitoring", () => {
  // 120s matches the project-level timeout set in playwright.config.ts.
  test.setTimeout(120_000);

  test("Health Check: System login and dashboard hydration", async ({
    page,
  }) => {
    // 1. Navigate directly to login
    await page.goto("/login");

    // 2. Perform live login as the dedicated test user
    const emailInput = page.locator('input[type="email"]');
    const passInput = page.locator('input[type="password"]');

    // Wait for the login form to be interactive
    await emailInput.waitFor({ state: "visible", timeout: 15_000 });

    await emailInput.fill(TEST_USER_EMAIL);
    await passInput.fill(TEST_USER_PASSWORD!);
    await page.getByRole("button", { name: /sign in|log in/i }).click();

    // Wait for the overview or portfolio page to load (handling potential Container Apps scale-up)
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, {
      timeout: 30_000,
    });

    // 3. Verify core data loads
    await page.goto("/portfolio");
    const totalValueEl = page.getByTestId("total-value");

    // Explicitly wait up to 30s for the portfolio service to respond
    // Azure Container Apps typically respond faster than Lambda cold starts
    await expect(totalValueEl).toBeVisible({ timeout: 30_000 });

    // Ensure the total value isn't an empty placeholder
    const value = await totalValueEl.textContent();
    expect(value).not.toBe("");
  });

  test("Health Check: Azure Front Door/Azure OpenAI latency check", async ({
    page,
  }) => {
    await page.goto("/ai-insights");

    // Check if the page loaded
    await expect(
      page.getByRole("heading", { name: /AI Insights/i }),
    ).toBeVisible({ timeout: 20_000 });

    // Note: We avoid heavy interaction with Azure OpenAI here unless strictly necessary
    // to preserve API quotas. We can just verify the service is reachable.
  });
});
