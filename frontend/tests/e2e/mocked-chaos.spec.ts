import { expect, test } from "@playwright/test";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

test.describe("Mocked Chaos Tests (Error Boundaries)", () => {
  test.beforeEach(async ({ page, request }) => {
    // Start with a valid authenticated session
    await installGatewaySessionInitScript(page, request);
  });

  test("503 Service Unavailable / Gateway Timeout graceful degradation", async ({ page }) => {
    // Mock the portfolio summary API to return a 503
    await page.route("**/api/portfolio/summary", async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ error: "Service Unavailable" }),
      });
    });

    await page.goto("/portfolio");

    // Ensure the application does not crash into a white screen
    // The UI is designed to degrade gracefully to zeroed out metrics without a blocking toast.
    await expect(page.getByTestId("total-value")).toBeVisible({ timeout: 10_000 });

    // The layout (e.g. sidebar, header) should still be intact
    await expect(page.getByRole("navigation")).toBeVisible();
  });

  test("429 Too Many Requests handles exponential backoff and limits retries", async ({ page }) => {
    let requestCount = 0;
    
    // Mock the market data API to return a 429
    await page.route("**/api/market/**", async (route) => {
      requestCount++;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "Too Many Requests" }),
      });
    });

    await page.goto("/market-data");

    // Wait for a few seconds to give TanStack Query time to potentially spam
    await page.waitForTimeout(5000); 

    // Assert that TanStack Query stopped retrying (e.g., max 2 or 3 requests total)
    expect(requestCount).toBeLessThanOrEqual(3); 
    
    // Assert the UI survived
    await expect(page.getByRole("navigation")).toBeVisible();
  });

  test("502 Bad Gateway fallback", async ({ page }) => {
    // Mock the chat/insights API
    await page.route("**/api/chat", async (route) => {
      await route.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({ error: "Bad Gateway" }),
      });
    });

    await page.goto("/ai-insights");
    
    // Simulate user sending a chat message
    const chatInput = page.getByTestId("chat-input");
    await chatInput.waitFor({ state: "visible", timeout: 15_000 });
    await chatInput.fill("How is AAPL doing?");
    await chatInput.press("Enter");

    // Verify a graceful error message appears in chat instead of a full app crash
    await expect(page.locator("text=Bad Gateway")).toBeVisible({ timeout: 15_000 });
  });
});
