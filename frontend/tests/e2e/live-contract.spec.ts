import { expect, test } from "@playwright/test";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

/**
 * Live Contract Verification Tests
 * 
 * Runs against the actual local stack to ensure HTTP contracts 
 * (headers, response shapes) are respected between frontend and backend.
 * NO NETWORK MOCKING ALLOWED HERE.
 */
test.describe("Live Contract Verification (Golden Path)", () => {
  test.beforeEach(async ({ page, request }) => {
    await installGatewaySessionInitScript(page, request);
  });

  test("Portfolio hydration matches API contract", async ({ page }) => {
    // Intercept but DO NOT mock - just observe the live traffic to verify the contract
    const portfolioResponsePromise = page.waitForResponse((response) =>
      response.url().includes("/api/portfolio/summary") && response.status() === 200
    );

    await page.goto("/portfolio");

    const response = await portfolioResponsePromise;
    const body = await response.json();

    // Verify the HTTP contract structure
    expect(body).toHaveProperty("totalValue");
    expect(body).toHaveProperty("totalHoldings");
    
    // Verify the UI renders based on the actual live data
    await expect(page.getByTestId("total-value")).toBeVisible({ timeout: 15_000 });
  });

  // RCA: data-testid="chat-input" unconditionally renders in ChatInterface.tsx (line 143).
  // Original failure was Lambda cold-start timeout on insight-service, not a missing element.
  // Timeout increases (CloudFront 60s, API GW 55s) and Golden State seeder resolve this.
  test("Bedrock AI Chat live interaction contract", async ({ page }) => {
    const chatResponsePromise = page.waitForResponse((response) => 
      response.url().includes("/api/chat") && response.request().method() === "POST"
    );

    await page.goto("/ai-insights");

    const chatInput = page.getByTestId("chat-input");
    await chatInput.waitFor({ state: "visible", timeout: 30_000 });
    await chatInput.fill("What is the current trend for tech stocks?");
    await chatInput.press("Enter");

    const response = await chatResponsePromise;
    expect(response.status()).toBe(200);

    const responseBody = await response.json();
    expect(responseBody).toHaveProperty("response");
  });
});
