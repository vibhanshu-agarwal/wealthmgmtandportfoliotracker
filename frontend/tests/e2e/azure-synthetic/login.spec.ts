import { expect, test } from "@playwright/test";

/**
 * Azure Synthetic Monitoring: Login Flow
 *
 * Verifies that the live production site handles the login flow
 * for the seeded E2E test user on Azure Container Apps.
 */

const TEST_USER_EMAIL =
  process.env.E2E_TEST_USER_EMAIL ?? "e2e-test-user@vibhanshu-ai-portfolio.dev";
const TEST_USER_PASSWORD = process.env.E2E_TEST_USER_PASSWORD;
// Mirror the Terraform variable: TF_VAR_app_auth_name = secrets.E2E_TEST_USER_NAME || 'Demo User'
// Using the same fallback keeps this assertion consistent with whatever name the
// deployed app actually shows, regardless of which secret value is configured.
const TEST_USER_NAME = process.env.E2E_TEST_USER_NAME ?? "Demo User";

test.describe("Azure Synthetic: Login", () => {
  test("Successful login to live production site", async ({ page }) => {
    await page.goto("/login");

    const emailInput = page.locator('input[type="email"]');
    const passInput = page.locator('input[type="password"]');

    // Wait for hydration (Next.js SSR to client-side hydration)
    await emailInput.waitFor({ state: "visible", timeout: 15_000 });

    await emailInput.fill(TEST_USER_EMAIL);
    await passInput.fill(TEST_USER_PASSWORD!);

    // Use getByRole for resilience against UI changes
    await page.getByRole("button", { name: /sign in|log in/i }).click();

    // Expect redirect to dashboard/overview
    // Azure Container Apps typically respond within 30s even during scale-up
    await expect(page).toHaveURL(/.*\/overview|.*\/portfolio/, {
      timeout: 30_000,
    });

    // Check for user identity in the UI.
    // TEST_USER_NAME mirrors TF_VAR_app_auth_name (secrets.E2E_TEST_USER_NAME || 'Demo User')
    // so the assertion matches whatever display name is actually seeded in the deployed app.
    await expect(page.locator("body")).toContainText(TEST_USER_NAME, {
      timeout: 10_000,
    });
  });
});
