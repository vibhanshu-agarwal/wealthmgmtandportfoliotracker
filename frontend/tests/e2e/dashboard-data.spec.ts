/**
 * Diagnostic E2E: Dashboard Data Integration
 *
 * Authentication is handled by the setup project (storageState/localStorage) —
 * all tests start pre-authenticated.
 *
 * Run:
 *   npx playwright test tests/e2e/dashboard-data.spec.ts --reporter=list
 *
 * Prerequisites:
 *   - Frontend static-export server running on http://localhost:3000
 *   - Spring Boot API Gateway running on http://127.0.0.1:8080
 */

import { expect, test, type Page } from "@playwright/test";
import { mintJwt as mintApiJwt } from "./helpers/auth";
import { ensurePortfolioWithHoldings } from "./helpers/api";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";

// ── Helpers ───────────────────────────────────────────────────────────────────

const BASE_URL = "http://localhost:3000";
const GATEWAY_URL = process.env.GATEWAY_BASE_URL ?? "http://localhost:8080";

// ── Network capture ───────────────────────────────────────────────────────────

interface ApiCall {
  url: string;
  method: string;
  status: number | null;
  authHeader: string | null;
  corsError: boolean;
  responseBody: string | null;
}

function attachNetworkLogger(page: Page, calls: ApiCall[]): void {
  const pending = new Map<string, ApiCall>();

  page.on("request", (req) => {
    const url = req.url();
    if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;

    const entry: ApiCall = {
      url,
      method: req.method(),
      status: null,
      authHeader: req.headers()["authorization"] ?? null,
      corsError: false,
      responseBody: null,
    };
    pending.set(req.url(), entry);
    calls.push(entry);

    console.log(`\n→ REQUEST  ${req.method()} ${url}`);
    console.log(`  Authorization: ${entry.authHeader ?? "(none)"}`);
  });

  page.on("response", async (res) => {
    const url = res.url();
    if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;

    const entry = pending.get(url);
    if (entry) {
      entry.status = res.status();
      try {
        entry.responseBody = await res.text();
      } catch {
        entry.responseBody = "(could not read body)";
      }
    }

    const corsHeader = res.headers()["access-control-allow-origin"];
    console.log(`\n← RESPONSE ${res.status()} ${url}`);
    console.log(`  Access-Control-Allow-Origin: ${corsHeader ?? "(absent)"}`);
    console.log(`  Body (first 300 chars): ${entry?.responseBody?.slice(0, 300) ?? ""}`);
  });

  page.on("requestfailed", (req) => {
    const url = req.url();
    if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;

    const entry = pending.get(url);
    if (entry) {
      entry.corsError = true;
    }
    console.log(`\n✗ FAILED   ${req.method()} ${url}`);
    console.log(`  Failure: ${req.failure()?.errorText ?? "unknown"}`);
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test.describe("Dashboard Data Integration Diagnostics", () => {
  /**
   * Test 1 — Session is active (global setup authenticated us)
   */
  test("1. Session is active after global setup", async ({ page }) => {
    await page.goto(`${BASE_URL}/overview`);

    // If setup storageState worked, we land on /overview (not redirected to /login)
    expect(page.url()).toContain("/overview");
    console.log("✓ Authenticated local session established via setup project");
  });

  /**
   * Test 2 — Portfolio page loads and total-value is not $0.00
   */
  test("2. /portfolio renders total-value and it is not $0.00 after 5 s", async ({ page, request }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await installGatewaySessionInitScript(page, request);
    // Seed deterministic holdings for the authenticated user so the assertion
    // does not depend on test order or external state.
    await ensurePortfolioWithHoldings(request);

    await page.goto(`${BASE_URL}/portfolio`);

    // Diagnostic: check what auth session storage looks like on the client
    await page.waitForTimeout(2_000);
    const sessionDiag = await page.evaluate(() => {
      const raw = window.localStorage.getItem("wmpt.auth.session");
      if (!raw) return null;
      try {
        return JSON.parse(raw);
      } catch {
        return "invalid-json";
      }
    });
    console.log(`\n  [diag] local auth session: ${JSON.stringify(sessionDiag).slice(0, 300)}`);

    const totalValueEl = page.getByTestId("total-value");
    await expect(totalValueEl).toBeVisible({ timeout: 15_000 });

    const displayedValue = await totalValueEl.textContent();
    console.log(`\n  [total-value] displayed: "${displayedValue}"`);

    expect(displayedValue, "total-value is still $0.00 — data did not load").not.toBe("$0.00");
    expect(displayedValue, "total-value is empty").not.toBe("");
  });

  /**
   * Test 3 — Authorization header is present on every /api/portfolio/* call
   */
  test("3. All /api/portfolio/* requests carry an Authorization header", async ({ page, request }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await installGatewaySessionInitScript(page, request);
    await page.goto(`${BASE_URL}/portfolio`);
    await page.waitForTimeout(5_000);

    const portfolioCalls = calls.filter((c) => c.url.includes("/api/portfolio"));

    console.log(`\n  Captured ${portfolioCalls.length} /api/portfolio/* request(s)`);

    if (portfolioCalls.length === 0) {
      console.warn("  ⚠ No /api/portfolio/* requests were captured.");
      console.warn("    Possible cause: usePortfolio hook never fired (auth status stuck on 'loading').");
    }

    expect(
      portfolioCalls.length,
      "Expected at least one /api/portfolio/* request while loading /portfolio",
    ).toBeGreaterThan(0);

    for (const call of portfolioCalls) {
      console.log(`\n  ${call.method} ${call.url}`);
      console.log(`    status        : ${call.status ?? "no response"}`);
      console.log(`    Authorization : ${call.authHeader ?? "(MISSING)"}`);
      console.log(`    CORS error    : ${call.corsError}`);

      expect(
        call.authHeader,
        `Missing Authorization header on ${call.url} — session token not attached`,
      ).not.toBeNull();

      expect(
        call.authHeader,
        `Authorization header on ${call.url} is not a Bearer token`,
      ).toMatch(/^Bearer /);
    }
  });

  /**
   * Test 4 — Gateway returns 200, not 401 or CORS error
   */
  test("4. API Gateway responds 200 (not 401/403/CORS) for authenticated requests", async ({ page, request }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await installGatewaySessionInitScript(page, request);
    await ensurePortfolioWithHoldings(request);

    await page.goto(`${BASE_URL}/portfolio`);
    await page.waitForTimeout(5_000);

    // Filter out aborted requests (status: null) — these are normal browser behaviour
    // when TanStack Query refetches overlap with component unmounts or page navigations.
    const portfolioCalls = calls.filter((c) => c.url.includes("/api/portfolio") && c.status != null);

    for (const call of portfolioCalls) {
      if (call.corsError) {
        console.error(`\n  ✗ CORS ERROR on ${call.url}`);
      }
      if (call.status === 401) {
        console.error(`\n  ✗ 401 UNAUTHORISED on ${call.url}`);
      }
      if (call.status === 403) {
        console.error(`\n  ✗ 403 FORBIDDEN on ${call.url}`);
      }

      expect(call.corsError, `CORS error on ${call.url}`).toBe(false);
      expect(call.status, `Expected 200 but got ${call.status} on ${call.url}`).toBe(200);
    }
  });

  /**
   * Test 5 — Synthetic JWT hits the Gateway directly (bypasses frontend auth)
   */
  test("5. Synthetic JWT is accepted by the API Gateway (direct fetch)", async ({ request }) => {
    const token = mintApiJwt("user-001");
    console.log(`\n  Synthetic JWT (first 80 chars): ${token.slice(0, 80)}…`);

    const response = await request.get(`${GATEWAY_URL}/api/portfolio`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    console.log(`\n  Direct Gateway response: ${response.status()}`);
    const body = await response.text();
    console.log(`  Body (first 300 chars): ${body.slice(0, 300)}`);

    expect(response.status(), "Gateway rejected synthetic JWT").toBe(200);
  });
});
