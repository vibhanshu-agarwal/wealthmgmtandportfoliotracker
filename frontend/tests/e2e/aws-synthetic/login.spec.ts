import { expect, test } from "@playwright/test";

/**
 * AWS Synthetic Monitoring: Login Flow
 * 
 * Verifies that the live production site handles the login flow
 * for the seeded E2E test user.
 */

const TEST_USER_EMAIL = process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
const TEST_USER_PASSWORD = process.env.E2E_TEST_USER_PASSWORD ?? "e2e-test-password-2026";

test.describe("AWS Synthetic: Login", () => {
  test("Successful login to live production site", async ({ page }) => {
    await page.goto("/login");

    const emailInput = page.locator('input[type="email"]');
    const passInput = page.locator('input[type="password"]');

    // Wait for hydration
    await emailInput.waitFor({ state: "visible", timeout: 15_000 });

    await emailInput.fill(TEST_USER_EMAIL);
    await passInput.fill(TEST_USER_PASSWORD);
    
    // Use getByRole for resilience
    await page.getByRole("button", { name: /sign in|log in/i }).click();

    // Expect redirect to dashboard/overview
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, {
      timeout: 30_000,
    });

    // Check for user identity in the UI
    await expect(page.locator("body")).toContainText("E2E Test User", { timeout: 10_000 });
  });
});
