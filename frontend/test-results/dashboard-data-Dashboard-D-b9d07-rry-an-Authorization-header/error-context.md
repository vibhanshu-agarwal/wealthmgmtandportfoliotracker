# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: dashboard-data.spec.ts >> Dashboard Data Integration Diagnostics >> 3. All /api/portfolio/* requests carry an Authorization header
- Location: tests\e2e\dashboard-data.spec.ts:160:7

# Error details

```
Test timeout of 120000ms exceeded.
```

```
Error: page.goto: net::ERR_ABORTED; maybe frame was detached?
Call log:
  - navigating to "http://127.0.0.1:3000/login", waiting until "load"

```

# Test source

```ts
  1   | /**
  2   |  * Diagnostic E2E: Dashboard Data Integration
  3   |  *
  4   |  * Purpose: Identify whether the "Blank Dashboard / $0.00" issue originates in
  5   |  *   (a) the API Gateway  — CORS policy or 401 Unauthorised, or
  6   |  *   (b) the frontend     — Auth context not ready / hook timing.
  7   |  *
  8   |  * Run:
  9   |  *   npx playwright test tests/e2e/dashboard-data.spec.ts --reporter=list
  10  |  *
  11  |  * Prerequisites:
  12  |  *   - Next.js dev/standalone server running on http://127.0.0.1:3000
  13  |  *   - Spring Boot API Gateway running on http://localhost:8080
  14  |  */
  15  | 
  16  | import { createHmac } from "node:crypto";
  17  | import { expect, test, type Page } from "@playwright/test";
  18  | 
  19  | // ── Helpers ───────────────────────────────────────────────────────────────────
  20  | 
  21  | const BASE_URL = "http://127.0.0.1:3000";
  22  | const AUTH_JWT_SECRET = process.env.AUTH_JWT_SECRET ?? "local-dev-secret-change-me-min-32-chars";
  23  | 
  24  | /** Mint a HS256 JWT using Node's built-in crypto — no extra deps needed. */
  25  | function mintJwt(userId = "user-001"): string {
  26  |   const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  27  |   const now = Math.floor(Date.now() / 1000);
  28  |   const payload = Buffer.from(
  29  |     JSON.stringify({ sub: userId, name: "Dev User", email: "dev@local", iat: now, exp: now + 3600 }),
  30  |   ).toString("base64url");
  31  |   const sig = createHmac("sha256", AUTH_JWT_SECRET)
  32  |     .update(`${header}.${payload}`)
  33  |     .digest("base64url");
  34  |   return `${header}.${payload}.${sig}`;
  35  | }
  36  | 
  37  | /** Perform a real credential login via the UI and return the page. */
  38  | async function loginViaUI(page: Page): Promise<void> {
> 39  |   await page.goto(`${BASE_URL}/login`);
      |              ^ Error: page.goto: net::ERR_ABORTED; maybe frame was detached?
  40  |   await page.fill('input[name="username"]', "user-001");
  41  |   await page.fill('input[name="password"]', "password");
  42  |   await page.click('button[type="submit"]');
  43  |   // Wait for redirect away from /login
  44  |   await page.waitForURL((url) => !url.pathname.includes("/login"), { timeout: 10_000 });
  45  | }
  46  | 
  47  | // ── Network capture ───────────────────────────────────────────────────────────
  48  | 
  49  | interface ApiCall {
  50  |   url: string;
  51  |   method: string;
  52  |   status: number | null;
  53  |   authHeader: string | null;
  54  |   corsError: boolean;
  55  |   responseBody: string | null;
  56  | }
  57  | 
  58  | function attachNetworkLogger(page: Page, calls: ApiCall[]): void {
  59  |   const pending = new Map<string, ApiCall>();
  60  | 
  61  |   page.on("request", (req) => {
  62  |     const url = req.url();
  63  |     if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;
  64  | 
  65  |     const entry: ApiCall = {
  66  |       url,
  67  |       method: req.method(),
  68  |       status: null,
  69  |       authHeader: req.headers()["authorization"] ?? null,
  70  |       corsError: false,
  71  |       responseBody: null,
  72  |     };
  73  |     pending.set(req.url(), entry);
  74  |     calls.push(entry);
  75  | 
  76  |     console.log(`\n→ REQUEST  ${req.method()} ${url}`);
  77  |     console.log(`  Authorization: ${entry.authHeader ?? "(none)"}`);
  78  |   });
  79  | 
  80  |   page.on("response", async (res) => {
  81  |     const url = res.url();
  82  |     if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;
  83  | 
  84  |     const entry = pending.get(url);
  85  |     if (entry) {
  86  |       entry.status = res.status();
  87  |       try {
  88  |         entry.responseBody = await res.text();
  89  |       } catch {
  90  |         entry.responseBody = "(could not read body)";
  91  |       }
  92  |     }
  93  | 
  94  |     const corsHeader = res.headers()["access-control-allow-origin"];
  95  |     console.log(`\n← RESPONSE ${res.status()} ${url}`);
  96  |     console.log(`  Access-Control-Allow-Origin: ${corsHeader ?? "(absent)"}`);
  97  |     console.log(`  Body (first 300 chars): ${entry?.responseBody?.slice(0, 300) ?? ""}`);
  98  |   });
  99  | 
  100 |   // Playwright surfaces failed requests (network errors, CORS blocks) here
  101 |   page.on("requestfailed", (req) => {
  102 |     const url = req.url();
  103 |     if (!url.includes("/api/portfolio") && !url.includes("/api/market")) return;
  104 | 
  105 |     const entry = pending.get(url);
  106 |     if (entry) {
  107 |       entry.corsError = true;
  108 |     }
  109 |     console.log(`\n✗ FAILED   ${req.method()} ${url}`);
  110 |     console.log(`  Failure: ${req.failure()?.errorText ?? "unknown"}`);
  111 |   });
  112 | }
  113 | 
  114 | // ── Tests ─────────────────────────────────────────────────────────────────────
  115 | 
  116 | test.describe("Dashboard Data Integration Diagnostics", () => {
  117 |   /**
  118 |    * Test 1 — Real login flow
  119 |    * Validates that the NextAuth credential flow produces a session that the
  120 |    * portfolio hooks can use to attach a Bearer token.
  121 |    */
  122 |   test("1. Login flow produces an authenticated session", async ({ page }) => {
  123 |     const calls: ApiCall[] = [];
  124 |     attachNetworkLogger(page, calls);
  125 | 
  126 |     await loginViaUI(page);
  127 | 
  128 |     // After login we should land on /overview (the post-login redirect)
  129 |     expect(page.url()).toContain("/overview");
  130 |     console.log("\n✓ Login succeeded — session cookie set");
  131 |   });
  132 | 
  133 |   /**
  134 |    * Test 2 — Portfolio page loads and total-value is not $0.00
  135 |    * This is the primary regression assertion.
  136 |    */
  137 |   test("2. /portfolio renders total-value and it is not $0.00 after 5 s", async ({ page }) => {
  138 |     const calls: ApiCall[] = [];
  139 |     attachNetworkLogger(page, calls);
```