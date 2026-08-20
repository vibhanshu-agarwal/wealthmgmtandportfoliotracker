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

  // SKIPPED: This test's assertion does not measure what its name claims. RCA:
  //   1. The name says "exponential backoff and limits retries", but this codebase
  //      never retries a 429. defaultQueryRetry (QueryProvider.tsx) returns false
  //      immediately for a RateLimitError / any 4xx, before the failureCount check.
  //   2. loadMarketPrices (portfolio.ts) batches unique tickers at
  //      MARKET_PRICE_BATCH_SIZE = 25 and fires the batches concurrently through
  //      Promise.allSettled, which absorbs every rejection ("on rejection: continue").
  //      fetchPortfolio therefore never throws, so defaultQueryRetry is never even
  //      consulted on this path.
  //   3. requestCount thus counts BATCHES, not retries: ceil(uniqueTickers / 25).
  //      It happened to pass at <= 3 only because the pre-Wave-0 dev identity held
  //      2 tickers (1 batch). B1 Wave 0 switched the shared login helper to the
  //      Golden-State identity (159 active catalog tickers -> 7 batches), so the
  //      assertion now fails deterministically at 7. The mismatch predates Wave 0;
  //      Wave 0 only exposed it.
  // Retry policy is already covered directly by QueryProvider.test.ts; batch
  // cardinality and partial-failure by portfolio.batching.test.ts. A proper
  // redesign of this end-to-end case (controlled fixed-holdings fixture, asserting
  // disjoint-batch-occurs-once and graceful degradation) is tracked in
  // docs/todos/backlog/mocked-chaos-429-batch-assertion-redesign/.
  test.skip("429 Too Many Requests handles exponential backoff and limits retries", async ({ page }) => {
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

    // Historical body, preserved for the quarantine (see the SKIP note above).
    // The waitForTimeout and the `<= 3` bound assume retry/backoff, but there is
    // no retry on this path: `requestCount` measures ticker-BATCH cardinality
    // (ceil(uniqueTickers / MARKET_PRICE_BATCH_SIZE)), not retry attempts. It is
    // 7 under the Golden-State identity (159 active tickers), so this assertion
    // fails deterministically -- which is why the test is skipped, not because
    // any backoff logic regressed.
    await page.waitForTimeout(5000);

    expect(requestCount).toBeLessThanOrEqual(3);

    // Assert the UI survived
    await expect(page.getByRole("navigation")).toBeVisible();
  });

  // SKIPPED: Confirmed failing in CI. RCA:
  //   1. installGatewaySessionInitScript POSTs to /api/auth/login on the local Spring stack.
  //      If the stack is cold or not yet ready, the login fails and beforeEach throws,
  //      leaving the page in an unauthenticated state where ChatInterface never hydrates.
  //   2. Even when auth succeeds, page.addInitScript only fires on the next goto() —
  //      useAuthSession's useLayoutEffect may race and read an empty localStorage first.
  // Pre-condition to re-enable: verify /api/auth/login responds in beforeEach, and
  // add an explicit waitForSelector on the chat-input after goto('/ai-insights').
  test.skip("502 Bad Gateway fallback", async ({ page }) => {
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
    await chatInput.waitFor({ state: "visible", timeout: 30_000 });
    await chatInput.fill("How is AAPL doing?");
    await chatInput.press("Enter");

    // Verify a graceful error message appears in chat instead of a full app crash
    await expect(page.locator("text=Bad Gateway")).toBeVisible({ timeout: 15_000 });
  });
});
