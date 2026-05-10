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

    // Assert the email address is visible in the UI (user menu, profile area, etc.).
    // Email is stable across all deployments and is already injected into every
    // workflow that runs this project, making this assertion independent of the
    // optional E2E_TEST_USER_NAME secret and its Terraform fallback.
    await expect(page.locator("body")).toContainText(TEST_USER_EMAIL, {
      timeout: 10_000,
    });
  });
});
