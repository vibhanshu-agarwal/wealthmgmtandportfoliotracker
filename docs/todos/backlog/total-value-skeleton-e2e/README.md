# Backlog: `total-value` E2E Test Stuck in Skeleton State

**Status:** ✅ Closed (2026-04-11)  
**Priority:** Medium  
**Area:** Frontend — Auth / TanStack Query / NextAuth  
**Spec:** `.kiro/specs/backend-remediation-e2e/`  
**Failing test:** `tests/e2e/golden-path.spec.ts` — "total-value is not $0.00 after holdings are seeded"  
**Resolution:** The extensive analysis of `useSession` loading states, TanStack Query `enabled` flags, and skeleton gate race conditions was all downstream of a simpler root cause: the standalone build was missing `.next/static/` JS bundles, so React never hydrated and no hooks ever fired. Fixed by updating `start:standalone` in `package.json` to copy static assets, public dir, and `.env.local` into the standalone output before starting. The `SummaryCards` component, its skeleton gate logic, and data mapping are all correct — no code changes needed. See `docs/changes/CHANGES_INFRA_SUMMARY_2026-04-11_v1.md`.

---

## Summary

The Playwright E2E test for `[data-testid="total-value"]` times out with "element(s) not found"
because the `SummaryCards` component stays in skeleton state indefinitely. The backend is
working correctly — `GET /api/portfolio/summary` returns `$57,839.25`. The issue is purely in
how the frontend session propagates during client-side SPA navigation in a headless browser
context.

---

## What Works

- `GET /api/portfolio` → 200, returns 3 holdings (AAPL, TSLA, BTC) ✅
- `GET /api/portfolio/summary` → 200, returns `totalValue: 57839.25` ✅
- `/api/auth/session` → 200, returns `accessToken` present ✅
- Playwright test 1 (Holdings Table shows AAPL + BTC) → **passing** ✅
- Playwright test 3 (Holdings Table tickers) → **passing** ✅
- Manual browser visit to `/portfolio` → renders correctly ✅

---

## What Fails

- Playwright test 2 (`total-value` is not `$0.00`) → **failing** ❌
- `[data-testid="total-value"]` never appears — skeleton stays forever
- No `/api/portfolio/summary` network request is made by the page during the test

---

## Root Cause Analysis

### The Auth Flow

The test helper `injectAuthSession(page)` works like this:

```typescript
// frontend/tests/e2e/helpers/auth.ts
export async function injectAuthSession(page: Page): Promise<void> {
  const token = mintSessionCookieJwt("00000000-0000-0000-0000-000000000001");

  await page.context().addCookies([
    {
      name: "authjs.session-token",
      value: token,
      domain: "127.0.0.1",
      path: "/",
    },
  ]);

  await page.goto("http://127.0.0.1:3000/overview");

  // Polls until accessToken is present
  await page.waitForFunction(
    async () => {
      const res = await fetch("/api/auth/session");
      const s = await res.json();
      return !!s?.accessToken;
    },
    undefined,
    { timeout: 15000, polling: 500 },
  );
}
```

After `injectAuthSession`, the test navigates to `/portfolio`:

```typescript
await injectAuthSession(page);
await page.goto("/portfolio");
```

### The Problem

When `page.goto("/portfolio")` triggers a **client-side SPA navigation**, the
`NextAuthSessionProvider` resets its internal session state to `"loading"` while it
re-fetches `/api/auth/session`. During this loading window:

```typescript
// frontend/src/lib/hooks/useAuthenticatedUserId.ts
export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data: session, status } = useSession();

  if (status === "authenticated" && session?.user?.id && session?.accessToken) {
    return {
      userId: session.user.id,
      token: session.accessToken,
      status: "authenticated",
    };
  }

  return { userId: "", token: "", status }; // ← returns "loading" here
}
```

All TanStack Query hooks are gated on `status === "authenticated"`:

```typescript
// frontend/src/lib/hooks/usePortfolio.ts
export function usePortfolioSummary() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.summary(userId),
    queryFn: () => fetchPortfolioSummary(userId, token),
    enabled: status === "authenticated" && !!token, // ← false during loading
    staleTime: 30_000,
    retry: 3,
    retryDelay: 1000,
  });
}
```

When `enabled: false`, TanStack Query puts the query in `pending` state with
`fetchStatus: "idle"`. The `SummaryCards` component checks `isSummaryFetching`:

```typescript
// frontend/src/components/portfolio/SummaryCards.tsx
const { data: portfolioSummary, isLoading: isSummaryLoading, isFetching: isSummaryFetching } =
  usePortfolioSummary();

if (isSummaryFetching && !portfolioSummary) return <SummaryCardsSkeleton />;
```

In TanStack Query v5, when a query is `pending` + `fetchStatus: "idle"`:

- `isFetching` = `false`
- `isLoading` = `false` (in v5, `isLoading` = `isPending && isFetching`)
- `isPending` = `true`

So the skeleton condition `isSummaryFetching && !portfolioSummary` is `false` — the skeleton
should NOT show. But the component still shows the skeleton because `isLoading` (from
`usePortfolio`) is `true` — `usePortfolio` is retrying the market prices 500 error.

The combined condition `if (isSummaryFetching && !portfolioSummary)` exits the skeleton, but
then `const showFullSkeleton = isLoading && !portfolioSummary` is `true` (because `usePortfolio`
is still loading/retrying), so the skeleton is shown again.

### Why `useSession()` Stays in `"loading"` During Navigation

The root layout pre-fetches the session server-side:

```typescript
// frontend/src/app/layout.tsx
export default async function RootLayout({ children }) {
  const session = await auth(); // server-side
  return (
    <SessionProvider session={session}>
      {children}
    </SessionProvider>
  );
}
```

This works for the **initial page load**. But when Playwright navigates from `/overview` to
`/portfolio` via `page.goto()`, Next.js performs a **full page navigation** (not a client-side
fetch), which re-runs the server component. The `auth()` call on the server reads the injected
cookie and returns a valid session. However, the `NextAuthSessionProvider` on the client still
goes through its initialization cycle, briefly returning `"loading"` before settling on
`"authenticated"`.

During that brief `"loading"` window, `usePortfolioSummary` is disabled. If the component
renders and the `usePortfolio` query (which was cached from `/overview`) is in a retrying state,
the skeleton condition fires and stays.

### Diagnostic Evidence

From the Playwright diagnostic test:

```
[diag] session state on /portfolio: {
  hasAccessToken: true,
  userId: '00000000-0000-0000-0000-000000000001',
  status: 'authenticated'
}
[diag] API calls after 5s: [ '200 http://127.0.0.1:3000/api/auth/session' ]
[diag] rewrite test from browser: {
  status: 200,
  body: '{"userId":"...","totalValue":57839.2500,"baseCurrency":"USD"}'
}
```

- Session is valid ✅
- `/api/portfolio/summary` is reachable from the browser ✅
- But **zero** `/api/portfolio` or `/api/portfolio/summary` calls are made by React ❌

The `SummaryCards` grid HTML shows skeleton elements (`animate-pulse`) — the component is
mounted but stuck in skeleton state.

---

## What Was Tried

| Attempt                                                                 | Result                                                                                            |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `waitForFunction` polling `/api/auth/session` for `accessToken`         | Session confirmed valid but hooks still don't fire                                                |
| `page.reload()` after session confirmed                                 | No API calls after reload either                                                                  |
| `page.waitForResponse("/api/portfolio/summary")`                        | Timeout — request never made                                                                      |
| Cookie-based synchronous token read in `useAuthenticatedUserId`         | `authjs.session-token` is `HttpOnly` — not readable via `document.cookie`                         |
| Server-side `auth()` pre-fetch in `layout.tsx`                          | Helps on initial load; SPA navigation still causes brief `"loading"` flash                        |
| `isFetching` instead of `isLoading` for skeleton guard                  | Skeleton exits but `usePortfolio` retry keeps `isLoading: true` → second skeleton condition fires |
| `summarySettled` guard (`portfolioSummary != null \|\| isSummaryError`) | Skeleton stays forever when query is disabled (never settles)                                     |

---

## Proposed Fix Approaches

### Option A — Decouple `total-value` from `usePortfolio` loading state (Recommended)

The `total-value` element only needs `portfolioSummary`. It should render independently of
`usePortfolio`'s loading/retry state. Split `SummaryCards` into two independent components:

```typescript
// TotalValueCard — only depends on usePortfolioSummary
function TotalValueCard() {
  const { data: portfolioSummary, isPending } = usePortfolioSummary();
  if (isPending) return <Skeleton />;
  return (
    <p data-testid="total-value">
      {formatCurrency(Number(portfolioSummary?.totalValue ?? 0))}
    </p>
  );
}
```

This way, `total-value` renders as soon as `usePortfolioSummary` resolves, regardless of
whether `usePortfolio` is still retrying market prices.

### Option B — Use `isPending` instead of `isLoading` for the full skeleton guard

In TanStack Query v5, `isLoading` = `isPending && isFetching`. A query that is `enabled: false`
has `isPending: true` but `isFetching: false`, so `isLoading: false`. Using `isPending` would
keep the skeleton showing while the query is disabled — but that's the wrong behaviour (we want
to show the skeleton only while actively fetching).

The correct check is `isFetching` — but only for the summary query, not for `usePortfolio`.

### Option C — Remove `enabled` guard, handle 401 gracefully

Remove `enabled: status === "authenticated" && !!token` and let the query fire immediately.
If the token is empty, the gateway returns 401. Handle 401 in `fetchWithAuthClient` by
returning `null` instead of throwing, and let the component render with `null` data.

This is the most resilient approach but requires changes to the error handling contract.

### Option D — Use `useSession` with `required: true` and a loading boundary

Wrap the portfolio page in a React Suspense boundary that waits for the session to be
`"authenticated"` before rendering `SummaryCards`. This prevents the component from mounting
at all during the `"loading"` window.

```typescript
// In the portfolio page layout
<SessionGate>
  <SummaryCards />
</SessionGate>

function SessionGate({ children }) {
  const { status } = useSession({ required: true });
  if (status === "loading") return <SummaryCardsSkeleton />;
  return children;
}
```

---

## Files Involved

| File                                                 | Role                                  |
| ---------------------------------------------------- | ------------------------------------- |
| `frontend/src/components/portfolio/SummaryCards.tsx` | Renders `total-value`; skeleton logic |
| `frontend/src/lib/hooks/usePortfolio.ts`             | `usePortfolioSummary` hook definition |
| `frontend/src/lib/hooks/useAuthenticatedUserId.ts`   | Session → token bridge                |
| `frontend/src/app/layout.tsx`                        | Server-side session pre-fetch         |
| `frontend/src/components/layout/SessionProvider.tsx` | NextAuth client provider              |
| `frontend/tests/e2e/helpers/auth.ts`                 | Cookie injection helper               |
| `frontend/tests/e2e/golden-path.spec.ts`             | Failing test                          |

---

## Reproduction Steps

1. Ensure Docker Compose is running (`postgres`, `kafka`, `redis`, `mongodb`)
2. Start all Spring Boot services
3. Build and start the Next.js standalone server:
   ```bash
   cd frontend
   npm run build
   cp .env.local .next/standalone/.env.local
   npm run start:standalone
   ```
4. Run the failing test:
   ```bash
   npx playwright test tests/e2e/golden-path.spec.ts --reporter=list
   ```
5. Observe test 2 fails with "element(s) not found" for `[data-testid="total-value"]`

To confirm the backend is working correctly:

```bash
# Mint a JWT and call the summary endpoint directly
TOKEN=$(node -e "
const { createHmac } = require('crypto');
const h = Buffer.from(JSON.stringify({alg:'HS256',typ:'JWT'})).toString('base64url');
const p = Buffer.from(JSON.stringify({sub:'00000000-0000-0000-0000-000000000001',iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+3600})).toString('base64url');
const s = createHmac('sha256','local-dev-secret-change-me-min-32-chars').update(h+'.'+p).digest('base64url');
console.log(h+'.'+p+'.'+s);
")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/portfolio/summary
# Expected: {"userId":"...","totalValue":57839.2500,"baseCurrency":"USD"}
```

---

## Acceptance Criteria for Fix

- `npx playwright test tests/e2e/golden-path.spec.ts` → all 3 tests pass
- `[data-testid="total-value"]` displays `$57,839.25` (or current market value) within 10s
- No regression on tests 1 and 3
- Fix works without `page.reload()` or `waitForTimeout` hacks in the test
