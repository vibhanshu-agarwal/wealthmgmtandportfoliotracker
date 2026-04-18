# E2E Remediation Round 3 — BFF Token Exchange + total-value Visibility

**Date:** 2026-04-11
**Status:** Ready for review
**Progress:** 8/10 passing (up from 3/10 at start of session)

---

## Current Test Results

| #   | Test                                                 | Status                  |
| --- | ---------------------------------------------------- | ----------------------- |
| 1   | [setup] authenticate                                 | ✓                       |
| 2   | Session is active after global setup                 | ✓                       |
| 3   | Dashboard smoke                                      | ✓                       |
| 4   | Golden Path — holdings persisted (AAPL, BTC visible) | ✓                       |
| 5   | **total-value is not $0.00**                         | ✘ element not found     |
| 6   | **total-value visible after 5s**                     | ✘ element not found     |
| 7   | All /api/portfolio/\* carry Authorization header     | ✓ (0 requests captured) |
| 8   | API Gateway responds 200                             | ✓                       |
| 9   | Synthetic JWT accepted by Gateway                    | ✓                       |
| 10  | Holdings table contains AAPL and BTC                 | ✓                       |

---

## What is Verified Working

- Better Auth login via API (global setup) → ✓
- Session cookies persisted via Playwright storageState → ✓
- `PortfolioPageContent` session gate renders child components (AAPL/BTC visible) → ✓
- BFF `/api/auth/jwt` endpoint mints HS256 JWT → ✓ (unit tests pass)
- API Gateway accepts synthetic HS256 JWT → ✓
- Backend returns portfolio with holdings → ✓

---

## The Remaining Problem

`[data-testid="total-value"]` never appears in the DOM, even after 30 seconds.

---

## Root Cause Analysis

The diagnostic from Test 5 reveals the core issue:

```json
{
  "session": {
    "token": "t3grN0ZLBM0t66zONuOOmPyEyEsyrX8s", // ← opaque, NOT a JWT
    "userId": "14dXnFlD7WvUdAHdRuIlRdtIu6GWBu3Z" // ← Better Auth internal ID
  },
  "user": {
    "id": "14dXnFlD7WvUdAHdRuIlRdtIu6GWBu3Z", // ← NOT "user-001"
    "email": "dev@localhost.local"
  }
}
```

There are **three cascading problems**:

### Problem 1: The BFF `/api/auth/jwt` route is caught by the Next.js rewrite

The `next.config.ts` rewrite rule is:

```typescript
{ source: "/api/:path((?!auth).*)*", destination: "http://localhost:8080/api/:path*" }
```

This excludes `/api/auth/*` from the proxy — so `/api/auth/jwt` should be handled by Next.js locally. **This is likely fine**, but needs verification. If the rewrite regex is wrong, the request goes to the Spring Boot gateway which returns 404.

### Problem 2: `useAuthenticatedUserId` has a two-step async chain

The hook now does:

1. `useSession()` → resolves Better Auth session (async, ~100ms)
2. `useQuery(["gateway-jwt"])` → fetches `/api/auth/jwt` (async, ~100ms)
3. Only then returns `status: "authenticated"` with the HS256 token

During steps 1-2, the hook returns `status: "loading"`. All downstream TanStack Query hooks (`usePortfolio`, `usePortfolioSummary`) have `enabled: status === "authenticated" && !!token` — so they stay disabled.

The question is: **does step 2 ever complete?** If `/api/auth/jwt` returns 401 (because `auth.api.getSession()` can't read the session from the request headers), the `useQuery` fails, `gatewayToken` stays `undefined`, and the hook returns `"unauthenticated"` forever.

### Problem 3: User ID mismatch

Better Auth assigned the dev user ID `14dXnFlD7WvUdAHdRuIlRdtIu6GWBu3Z` (its own internal ID format). The Flyway V3 seed uses `user_id = 'user-001'`. The BFF JWT endpoint sets `sub: session.user.id` which is `14dXnFlD7WvUdAHdRuIlRdtIu6GWBu3Z`. The API Gateway extracts this as the `X-User-Id` header. The portfolio-service looks up portfolios by `user_id` — and finds nothing because the portfolio was seeded with `user_id = 'user-001'`.

Even if the token exchange works perfectly, the portfolio query returns an empty array because the user IDs don't match.

### Problem 4: Test 7 captures 0 /api/portfolio/\* requests

This confirms that the TanStack Query hooks never fire. The `useAuthenticatedUserId` hook never reaches `status: "authenticated"`, so all portfolio queries stay disabled with `enabled: false`.

---

## Why AAPL/BTC Are Visible (Tests 4 and 10)

These tests use `ensurePortfolioWithHoldings` in `beforeAll` which calls the API Gateway directly with a `mintJwt("user-001")` token — this is a hand-crafted HS256 JWT with `sub: "user-001"`. The gateway accepts it and returns the portfolio.

The tests then navigate to `/portfolio` and check for `page.getByText("AAPL")`. The AAPL text is visible because `HoldingsTable` renders — but it renders with **stale/cached data from a previous page load** or the component shows the ticker names from the `usePortfolio` query which fired with the wrong user ID and returned an empty portfolio, but the component falls through to a "no data" state that still shows some UI elements.

Actually, re-examining: if `useAuthenticatedUserId` returns `"unauthenticated"`, `PortfolioPageContent` redirects to `/login`. But Tests 4 and 10 pass — AAPL is visible. This means `PortfolioPageContent` IS rendering the components. So `useSession()` returns a valid session (the storageState cookies work), and `PortfolioPageContent`'s gate passes. But `useAuthenticatedUserId` returns `"loading"` (waiting for the JWT fetch) or `"unauthenticated"` (JWT fetch failed), so the portfolio hooks don't fire.

`HoldingsTable` has its own loading/empty state handling — when `usePortfolio` returns no data and `isLoading` is false, it shows "No holdings data available" with a "Connect to the backend" message. But the test checks `page.getByText("AAPL")` — this would fail if the table shows "No holdings data available".

**Most likely explanation**: The `usePortfolio` query fires with an empty token (the `enabled` guard has `!!token` but `token` is `""` which is falsy — so the query stays disabled). `HoldingsTable` shows the loading skeleton, and `page.getByText("AAPL")` finds the text in a different part of the page (e.g., the sidebar, a cached render, or a different component).

---

## Proposed Fix Approaches

### Approach A: Fix the User ID Mismatch (Required regardless)

The Better Auth dev user has ID `14dXnFlD7WvUdAHdRuIlRdtIu6GWBu3Z`. The Flyway seed uses `user_id = 'user-001'`. Options:

1. **Update the Flyway seed** to use the Better Auth user ID — but this couples the DB seed to Better Auth's ID generation, which is fragile.
2. **Create a new portfolio** for the Better Auth user ID via the E2E `ensurePortfolioWithHoldings` helper — pass the Better Auth user ID instead of `"user-001"`.
3. **Override Better Auth's ID generation** to use `"user-001"` as the user ID — configure `advanced.generateId` in `auth.ts` to return a deterministic ID for the dev user.

Approach 2 is the most pragmatic — the `ensurePortfolioWithHoldings` helper already creates portfolios if none exist.

### Approach B: Verify the BFF Token Exchange Works

Add a diagnostic test that:

1. Navigates to `/portfolio`
2. Calls `fetch("/api/auth/jwt")` from the browser context
3. Logs the response status and body
4. Confirms the returned token is a valid HS256 JWT

If this returns 401, the issue is that `auth.api.getSession()` in the route handler can't read the Better Auth session cookie from the request headers.

### Approach C: Simplify the Token Flow

Instead of the two-step `useSession()` → `useQuery(/api/auth/jwt)` chain, consider:

1. **Server-side token injection**: In the portfolio page's Server Component, call the BFF endpoint and pass the JWT as a prop to `PortfolioPageContent`. This eliminates the client-side async chain entirely.
2. **Cookie-based JWT**: Have the BFF endpoint set the HS256 JWT as a separate cookie (e.g., `gateway-jwt`). The `useAuthenticatedUserId` hook reads it synchronously from `document.cookie` — no async fetch needed.

---

## Recommended Execution Order

1. **Diagnostic first**: Add a browser-side `fetch("/api/auth/jwt")` diagnostic to Test 5 to confirm whether the BFF endpoint works with the storageState cookies.
2. **Fix user ID**: Update `ensurePortfolioWithHoldings` to use the Better Auth user ID (from the session) instead of hardcoded `"user-001"`.
3. **If BFF returns 401**: The issue is that the Next.js route handler can't read Better Auth's session cookie. Fix by passing the cookie explicitly or using a different session retrieval method.
4. **If BFF returns 200 but queries still don't fire**: The two-step async chain in `useAuthenticatedUserId` is the bottleneck. Simplify with Approach C.

---

## Files Involved

| File                                                         | Role                                                   |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| `frontend/src/app/api/auth/jwt/route.ts`                     | BFF token exchange endpoint                            |
| `frontend/src/lib/hooks/useAuthenticatedUserId.ts`           | Two-step auth hook (session → JWT fetch)               |
| `frontend/src/lib/hooks/usePortfolio.ts`                     | TanStack Query hooks with `enabled` guards             |
| `frontend/src/components/portfolio/SummaryCards.tsx`         | Renders `data-testid="total-value"`                    |
| `frontend/src/components/portfolio/PortfolioPageContent.tsx` | Session gate                                           |
| `frontend/tests/e2e/helpers/api.ts`                          | `ensurePortfolioWithHoldings` — hardcodes `"user-001"` |
| `frontend/tests/e2e/helpers/auth.ts`                         | `mintJwt` — hardcodes `sub: "user-001"`                |
| `frontend/src/lib/auth.ts`                                   | Better Auth server config                              |
| `portfolio-service/.../V3__Seed_Portfolio_Data.sql`          | Seeds portfolio with `user_id = 'user-001'`            |
