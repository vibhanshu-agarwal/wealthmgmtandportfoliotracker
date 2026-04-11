# E2E Test Remediation Plan — Better Auth Migration

**Status:** Partially resolved (2026-04-11)  
**Priority:** Medium (downgraded — remaining failure is non-critical)  
**Date:** 2026-04-11  
**Latest results:** 8/9 tests passing. See resolution notes below.

---

## Test Results Summary

- 3 passed, 5 failed, 1 skipped (did not run due to prior failure in same describe block)

---

## Issue Analysis

### Issue 1: Login form submitting as GET instead of POST (3 tests affected)

**Tests:** Test 1 (login flow), Test 2 (total-value), Test 6 (total-value golden-path)

**Symptom:** After `injectAuthSession` runs, the URL is:

```
http://localhost:3000/login?email=dev%40localhost.local&password=password
```

The page never leaves `/login`. The credentials appear as query parameters — the form submitted as a native HTML GET instead of the React `handleSubmit` handler firing.

**Root cause:** Playwright clicks the submit button before React has hydrated the component. The `onSubmit` handler with `e.preventDefault()` isn't attached yet, so the browser performs a default form submission (GET with query params). This is the same hydration race we saw with NextAuth.

**Fix:** In `injectAuthSession` (`frontend/tests/e2e/helpers/auth.ts`):

- Add `await page.waitForLoadState("networkidle")` after `page.goto` (already present — verify it's working)
- Additionally wait for the submit button to be enabled/interactive before clicking: `await page.getByRole("button", { name: "Sign in" }).waitFor({ state: "visible" })`
- OR add a small `waitForTimeout(500)` after `networkidle` to ensure React hydration completes
- The most robust approach: wait for a React-specific signal, e.g. `await page.waitForFunction(() => document.querySelector('form')?.dataset?.hydrated === 'true')` after adding a `data-hydrated` attribute in the Login component's `useEffect`

### Issue 2: API Gateway not running (3 tests affected)

**Tests:** Test 5 (synthetic JWT), Test 1 & 4 in golden-path (ensurePortfolioWithHoldings)

**Symptom:**

```
connect ECONNREFUSED ::1:8080
```

**Root cause:** The Spring Boot API Gateway is not running. The `::1` in the error shows Node.js is resolving `localhost` to IPv6 `::1` instead of IPv4 `127.0.0.1`, and the gateway only binds to IPv4.

**Fix (two parts):**

1. **Start the API Gateway** before running E2E tests — this is a prerequisite, not a code fix
2. **IPv6 resolution fix** in `frontend/tests/e2e/helpers/api.ts`: Change `GATEWAY_URL` from `http://localhost:8080` to `http://127.0.0.1:8080` to force IPv4 resolution. Node.js 18+ defaults to IPv6 when resolving `localhost`, which fails if the gateway only listens on `0.0.0.0` (IPv4).

### Issue 3: total-value element not found (cascading from Issue 1)

**Tests:** Test 2 (dashboard-data), Test 6 (golden-path total-value)

**Symptom:** `getByTestId('total-value')` times out after 15s.

**Root cause:** This is a cascading failure from Issue 1. Since login fails, the user is never authenticated, `PortfolioPageContent` returns `null` (unauthenticated gate), and `SummaryCards` never mounts. Once Issue 1 is fixed, this should resolve automatically.

**No independent fix needed** — depends on Issue 1 resolution.

### Issue 4: Test 5 skipped (golden-path holdings table)

**Test:** "holdings table contains AAPL and BTC tickers"

**Symptom:** `1 did not run`

**Root cause:** This test is in the same `test.describe` block as the failing total-value test. The `test.beforeAll` in that describe block calls `ensurePortfolioWithHoldings` which fails due to Issue 2 (ECONNREFUSED). When `beforeAll` fails, Playwright skips all tests in the block.

**No independent fix needed** — depends on Issue 2 resolution.

---

## Resolution Status (2026-04-11)

### ✅ Issue 1: Login hydration race — RESOLVED

The global setup was migrated to Better Auth's API-based sign-in (`/api/auth/sign-in/email`), eliminating the Playwright form submission hydration race entirely. The `global.setup.ts` now authenticates via API and saves `storageState` cookies.

### ✅ Issue 2: API Gateway not running / IPv6 — RESOLVED

The gateway URL was updated to `http://127.0.0.1:8080`. The API Gateway is confirmed running and accepting requests.

### ✅ Issue 3: total-value element not found — RESOLVED

Root cause was NOT a cascading auth failure. The standalone Next.js build was missing `.next/static/` JS bundles, preventing React hydration. Fixed by updating `start:standalone` in `package.json` to copy `static/`, `public/`, and `.env.local` into the standalone output.

### ✅ Issue 4: Test 5 skipped — RESOLVED

No longer skipped. The `beforeAll` hook succeeds now that the gateway is reachable.

### ⚠️ Remaining: Test 4 (`API Gateway responds 200 for authenticated requests`) — NEW ISSUE

This test captures all `/api/portfolio/*` network requests and asserts they all return 200. It fails because some requests show `status: null` — these are requests that were aborted by the browser when the page navigated away before the response arrived. This is a test timing issue, not a backend issue. The market-data-service being down (port 8082) also causes `/api/market/prices` to return 500, which may cascade into aborted portfolio requests.

**To fully resolve:** Either start market-data-service before E2E tests, or update test 4 to filter out `null`-status (aborted) requests from its assertions.

---

## Original Remediation Plan (preserved for reference)

### Step 1: Fix the IPv6 resolution issue in api.ts — ✅ DONE

### Step 2: Fix the login hydration race in auth.ts — ✅ DONE (replaced with API-based auth)

### Step 3: Verify prerequisites before running

- Ensure Docker Compose infra is up: `docker compose up -d postgres kafka redis mongodb`
- Ensure API Gateway is running: `./gradlew :api-gateway:bootRun`
- Ensure portfolio-service is running: `./gradlew :portfolio-service:bootRun`
- Ensure Next.js standalone is running with `.env.local` copied
- **NEW:** Ensure market-data-service is running for full 9/9 pass: `./gradlew :market-data-service:bootRun`

### Step 4: Run tests and verify

- `cd frontend && npm run test:e2e`
- Expected: 8/9 pass without market-data-service, 9/9 with it

---

## Files to Modify

| File                                        | Change                            |
| ------------------------------------------- | --------------------------------- |
| `frontend/tests/e2e/helpers/api.ts`         | IPv4 gateway URL                  |
| `frontend/tests/e2e/helpers/auth.ts`        | Hydration wait before form submit |
| `frontend/tests/e2e/dashboard-data.spec.ts` | IPv4 gateway URL in Test 5        |
