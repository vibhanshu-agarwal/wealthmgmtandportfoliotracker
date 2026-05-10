import { expect, test } from "@playwright/test";

/**
 * Azure Synthetic Monitoring: AI Insights page smoke
 *
 * Verifies that the /ai-insights route loads, the authenticated user's session
 * is carried through, and the two main panels (MarketSummaryGrid +
 * ChatInterface) are present and fully settled.
 *
 * This test intentionally does NOT submit a chat message. Triggering an
 * Azure OpenAI request on every hourly synthetic run would burn quota and
 * make the suite flaky on API latency variance. AI generation is not part of
 * the Phase 2 acceptance criteria.
 */

test.describe("Azure Synthetic: AI Insights", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/login");
    await page
      .locator('input[type="email"]')
      .fill(
        process.env.E2E_TEST_USER_EMAIL ??
          "e2e-test-user@vibhanshu-ai-portfolio.dev",
      );
    await page
      .locator('input[type="password"]')
      .fill(process.env.E2E_TEST_USER_PASSWORD!);
    await page.getByRole("button", { name: /sign in|log in/i }).click();
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/);
  });

  test("AI Insights page loads: heading, chat panel, and market summary settled", async ({
    page,
  }) => {
    await page.goto("/ai-insights");

    // 1. Page heading confirms the route resolved and the layout rendered.
    await expect(
      page.getByRole("heading", { name: "AI Insights" }),
    ).toBeVisible({ timeout: 20_000 });

    // 2. chat-input confirms ChatInterface mounted and the session is still
    //    authenticated after the navigation (unauthenticated state hides the input).
    await expect(page.getByTestId("chat-input")).toBeVisible({
      timeout: 20_000,
    });

    // 3. MarketSummaryGrid must reach one of its three terminal states —
    //    any state means the data fetch resolved and the skeleton is gone.
    //    We accept:
    //      market-summary-grid  – data returned at least one entry
    //      market-summary-empty – API returned an empty dataset
    //      market-summary-error – API returned an error (surface it, don't hide it)
    await expect(
      page.locator(
        '[data-testid="market-summary-grid"],' +
          '[data-testid="market-summary-empty"],' +
          '[data-testid="market-summary-error"]',
      ),
    ).toBeVisible({ timeout: 30_000 });
  });
});
