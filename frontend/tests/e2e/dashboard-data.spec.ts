/**
 * Diagnostic E2E: Dashboard Data Integration
 *
 * Purpose: Identify whether the "Blank Dashboard / $0.00" issue originates in
 *   (a) the API Gateway  — CORS policy or 401 Unauthorised, or
 *   (b) the frontend     — Auth context not ready / hook timing.
 *
 * Run:
 *   npx playwright test tests/e2e/dashboard-data.spec.ts --reporter=list
 *
 * Prerequisites:
 *   - Next.js dev/standalone server running on http://127.0.0.1:3000
 *   - Spring Boot API Gateway running on http://localhost:8080
 */

import { createHmac } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";

// ── Helpers ───────────────────────────────────────────────────────────────────

const BASE_URL = "http://127.0.0.1:3000";
const AUTH_JWT_SECRET = process.env.AUTH_JWT_SECRET ?? "local-dev-secret-change-me-min-32-chars";

/** Mint a HS256 JWT using Node's built-in crypto — no extra deps needed. */
function mintJwt(userId = "user-001"): string {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(
    JSON.stringify({ sub: userId, name: "Dev User", email: "dev@local", iat: now, exp: now + 3600 }),
  ).toString("base64url");
  const sig = createHmac("sha256", AUTH_JWT_SECRET)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${sig}`;
}

/**
 * Establish an authenticated session by injecting a signed JWT cookie.
 *
 * The NextAuth v5 beta client-side signIn() is unreliable in CI: getProviders()
 * can return null (e.g. timing, server cold-start), which triggers a hard
 * redirect to /api/auth/error — ignoring `redirect: false`.
 * Cookie injection uses the same HS256 key as auth.ts encode/decode, so the
 * server accepts it as a valid session.
 */
async function loginViaUI(page: Page): Promise<void> {
  const token = mintJwt("user-001");
  await page.context().addCookies([
    {
      name: "authjs.session-token",
      value: token,
      domain: "127.0.0.1",
      path: "/",
    },
  ]);
  await page.goto(`${BASE_URL}/overview`);
}

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

  // Playwright surfaces failed requests (network errors, CORS blocks) here
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
   * Test 1 — Session cookie is accepted by the NextAuth server
   * Validates that a HS256-signed JWT cookie produces a valid session
   * that the portfolio hooks can use to attach a Bearer token.
   */
  test("1. Login flow produces an authenticated session", async ({ page }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await loginViaUI(page);

    // Verify the server accepted the cookie and returned a session
    const session = await page.evaluate(async () => {
      const res = await fetch("/api/auth/session");
      return res.json();
    });
    console.log(`\n  Session: ${JSON.stringify(session)}`);

    expect(session?.user?.id, "Session should contain user id").toBe("user-001");
    expect(page.url()).toContain("/overview");
    console.log("✓ Authenticated session established");
  });

  /**
   * Test 2 — Portfolio page loads and total-value is not $0.00
   * This is the primary regression assertion.
   */
  test("2. /portfolio renders total-value and it is not $0.00 after 5 s", async ({ page }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await loginViaUI(page);
    await page.goto(`${BASE_URL}/portfolio`);

    // Wait for the element to appear (skeleton → real value)
    const totalValueEl = page.getByTestId("total-value");
    await expect(totalValueEl).toBeVisible({ timeout: 10_000 });

    const displayedValue = await totalValueEl.textContent();
    console.log(`\n  [total-value] displayed: "${displayedValue}"`);

    // Fail the test if the value is still the zero-state placeholder
    expect(displayedValue, "total-value is still $0.00 — data did not load").not.toBe("$0.00");
    expect(displayedValue, "total-value is empty").not.toBe("");
  });

  /**
   * Test 3 — Authorization header is present on every /api/portfolio/* call
   * Detects the "hook fires before session is ready" timing bug.
   */
  test("3. All /api/portfolio/* requests carry an Authorization header", async ({ page }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await loginViaUI(page);
    await page.goto(`${BASE_URL}/portfolio`);

    // Give React Query time to fire all queries
    await page.waitForTimeout(5_000);

    const portfolioCalls = calls.filter((c) => c.url.includes("/api/portfolio"));

    console.log(`\n  Captured ${portfolioCalls.length} /api/portfolio/* request(s)`);

    if (portfolioCalls.length === 0) {
      console.warn("  ⚠ No /api/portfolio/* requests were captured.");
      console.warn("    Possible cause: usePortfolio hook never fired (auth status stuck on 'loading').");
    }

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
   * Distinguishes between a Spring Security misconfiguration and a frontend issue.
   */
  test("4. API Gateway responds 200 (not 401/403/CORS) for authenticated requests", async ({ page }) => {
    const calls: ApiCall[] = [];
    attachNetworkLogger(page, calls);

    await loginViaUI(page);
    await page.goto(`${BASE_URL}/portfolio`);
    await page.waitForTimeout(5_000);

    const portfolioCalls = calls.filter((c) => c.url.includes("/api/portfolio"));

    for (const call of portfolioCalls) {
      if (call.corsError) {
        console.error(`\n  ✗ CORS ERROR on ${call.url}`);
        console.error("    Fix location: API Gateway — add CORS config for http://localhost:3000");
        console.error("    Check: api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java");
      }

      if (call.status === 401) {
        console.error(`\n  ✗ 401 UNAUTHORISED on ${call.url}`);
        console.error("    Fix location: API Gateway — JWT decoder or SecurityConfig");
        console.error("    Check: JwtDecoderConfig.java — does AUTH_JWT_SECRET match .env.local?");
      }

      if (call.status === 403) {
        console.error(`\n  ✗ 403 FORBIDDEN on ${call.url}`);
        console.error("    Fix location: API Gateway — SecurityConfig route permissions");
      }

      expect(call.corsError, `CORS error on ${call.url}`).toBe(false);
      expect(call.status, `Expected 200 but got ${call.status} on ${call.url}`).toBe(200);
    }
  });

  /**
   * Test 5 — Synthetic JWT (bypasses NextAuth) hits the Gateway directly
   * Isolates whether the problem is in NextAuth token generation or the Gateway decoder.
   */
  test("5. Synthetic JWT is accepted by the API Gateway (direct fetch)", async ({ request }) => {
    const token = mintJwt("user-001");
    console.log(`\n  Synthetic JWT (first 80 chars): ${token.slice(0, 80)}…`);

    const response = await request.get("http://localhost:8080/api/portfolio", {
      headers: { Authorization: `Bearer ${token}` },
    });

    console.log(`\n  Direct Gateway response: ${response.status()}`);
    const body = await response.text();
    console.log(`  Body (first 300 chars): ${body.slice(0, 300)}`);

    if (response.status() === 401) {
      console.error("\n  ✗ Gateway rejected the synthetic JWT.");
      console.error("    Check: Does AUTH_JWT_SECRET in .env.local match auth.jwt.secret in application-local.yml?");
      console.error("    Check: JwtDecoderConfig.java — algorithm must be HS256");
    }

    if (response.status() === 0 || body.includes("ECONNREFUSED")) {
      console.warn("\n  ⚠ Could not reach http://localhost:8080 — is the API Gateway running?");
    }

    expect(response.status(), "Gateway rejected synthetic JWT — secret mismatch or wrong algorithm").toBe(200);
  });
});
