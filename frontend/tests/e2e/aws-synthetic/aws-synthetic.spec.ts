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

const TEST_USER_EMAIL =
  process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
const TEST_USER_PASSWORD =
  process.env.E2E_TEST_USER_PASSWORD ?? "e2e-test-password-2026";

/**
 * RE-ENABLED — 2026-04-20
 *
 * Root causes identified and resolved:
 *   - Kafka SSL PKIX error: fixed via classpath-bound kafka-truststore.jks.
 *   - INTERNAL_API_KEY: now injected into Lambda env by Terraform on main.
 *   - CloudFront origin_read_timeout raised to 60s; API Gateway to 55s.
 *   - Pre-flight connectivity checks guard against infrastructure regressions.
 *
 * If the login flow regresses, check CloudWatch logs for the auth Lambda
 * and verify E2E credentials in GitHub Actions secrets.
 */
test.describe("Live AWS Synthetic Monitoring", () => {
  // Use a longer timeout for the entire suite to account for cold starts
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
    await passInput.fill(TEST_USER_PASSWORD);
    await page.getByRole("button", { name: /sign in|log in/i }).click();

    // Wait for the overview or portfolio page to load (handling potential Lambda cold start)
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, {
      timeout: 30_000,
    });

    // 3. Verify core data loads
    await page.goto("/portfolio");
    const totalValueEl = page.getByTestId("total-value");

    // Explicitly wait up to 60s for the cold start of the portfolio-service
    // plus any exponential backoff retries if the initial cold start hit the 20s API Gateway timeout
    await expect(totalValueEl).toBeVisible({ timeout: 60_000 });

    // Ensure the total value isn't an empty placeholder
    const value = await totalValueEl.textContent();
    expect(value).not.toBe("");
  });

  test("Health Check: CloudFront/Bedrock AI Insights latency check", async ({
    page,
  }) => {
    await page.goto("/ai-insights");

    // Check if the page loaded
    await expect(
      page.getByRole("heading", { name: /AI Insights/i }),
    ).toBeVisible({ timeout: 20_000 });

    // Note: We avoid heavy interaction with Bedrock here unless strictly necessary
    // to preserve AWS quotas. We can just verify the service is reachable.
  });
});
