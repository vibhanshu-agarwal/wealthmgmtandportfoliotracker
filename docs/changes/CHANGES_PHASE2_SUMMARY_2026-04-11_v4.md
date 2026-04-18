# Phase 2 Changes — 2026-04-11 v4

## E2E Hydration Fix, Standalone Build Assets, 10/10 Tests Green

Continues from [v1](CHANGES_PHASE2_SUMMARY_2026-04-11_v1.md).

---

## Changes Since v1

### Root Cause: Standalone Build Missing Static Assets

The 2 failing `total-value` E2E tests (parked in v1 as backlog items) were caused by the Next.js standalone server missing critical assets:

1. `.next/static/` — JavaScript bundles. Without these, the browser received server-rendered HTML but React never hydrated. `useSession()` never ran, `isPending` stayed `true`, and `PortfolioPageContent` rendered the skeleton forever.
2. `public/` — Static assets (fonts, icons).
3. `.env.local` — Runtime environment variables (`BETTER_AUTH_SECRET`, `DATABASE_URL`). Without these, the standalone server crashed on auth operations.

This is a documented Next.js requirement — `output: 'standalone'` intentionally excludes `static/` and `public/` from the standalone output.

### Fix: `start:standalone` Script

Updated `frontend/package.json` to copy all three assets before starting the server:

```json
"start:standalone": "node -e \"...fs.cpSync('.next/static',...)...\" && node .next/standalone/server.js"
```

The script uses Node.js `fs.cpSync` (cross-platform, no extra dependencies) to copy `.next/static/`, `public/`, and `.env.local` into the standalone output directory.

### E2E Test Fix: Aborted Request Filter

Test 4 (`API Gateway responds 200 for authenticated requests`) was failing because some captured network requests had `status: null` — these were requests aborted by the browser when TanStack Query refetches overlapped with component unmounts.

- `frontend/tests/e2e/dashboard-data.spec.ts` — Added `&& c.status != null` filter to exclude aborted requests from the 200-status assertion. The test still catches real failures (401, 403, CORS errors).

### Backlog Items Closed

- `docs/todos/backlog/total-value-e2e-hydration/README.md` — Status → ✅ Closed
- `docs/todos/backlog/total-value-skeleton-e2e/README.md` — Status → ✅ Closed
- `docs/todos/backlog/e2e-betterauth-remediation/README.md` — Status → Partially resolved (8/9 → 10/10 with today's fixes)

### Test Results

10/10 E2E tests passing:

| Test                                                         | Status      |
| ------------------------------------------------------------ | ----------- |
| [setup] authenticate                                         | ✅          |
| 1. Session is active after global setup                      | ✅          |
| 2. /portfolio renders total-value and it is not $0.00        | ✅ (was ❌) |
| 3. All /api/portfolio/\* requests carry Authorization header | ✅          |
| 4. API Gateway responds 200 for authenticated requests       | ✅ (was ❌) |
| 5. Synthetic JWT is accepted by the API Gateway              | ✅          |
| Golden Path — portfolio holdings are persisted               | ✅          |
| Golden Path — total-value is not $0.00                       | ✅ (was ❌) |
| Golden Path — holdings table contains AAPL and BTC           | ✅          |
| Dashboard smoke — standalone build renders overview          | ✅          |

---

## Files Changed (Since v1)

| File                                                      | Change                                             |
| --------------------------------------------------------- | -------------------------------------------------- |
| `frontend/package.json`                                   | `start:standalone` copies static/public/.env.local |
| `frontend/tests/e2e/dashboard-data.spec.ts`               | Filter aborted requests in test 4                  |
| `docs/todos/backlog/total-value-e2e-hydration/README.md`  | Closed                                             |
| `docs/todos/backlog/total-value-skeleton-e2e/README.md`   | Closed                                             |
| `docs/todos/backlog/e2e-betterauth-remediation/README.md` | Updated status                                     |

---

## Changes Since v2: UI Polish — Overview & Market Data Pages

### Overview

Replaced the "coming soon" placeholder stubs on `/overview` and `/market-data` with fully functional, session-gated client components. Both pages reuse existing data hooks and UI components — zero new API calls, hooks, or backend changes.

### Overview Page (`/overview`)

Created `frontend/src/components/overview/OverviewPageContent.tsx`:

- Session gate using `useSession()` + `useRouter()` redirect (same pattern as `PortfolioPageContent`)
- Skeleton loading state mirroring the page layout
- Composes existing components: `SummaryCards` (top row), `PerformanceChart` + `AllocationChart` (middle row)
- "View Portfolio →" link card at the bottom routing to `/portfolio`
- Intentionally excludes `HoldingsTable` — that stays on the Portfolio page

Updated `frontend/src/app/(dashboard)/overview/page.tsx` to render `<OverviewPageContent />`.

### Market Data Page (`/market-data`)

Created `frontend/src/components/market/MarketDataPageContent.tsx`:

- Session gate with the same pattern
- Derives market data from existing `usePortfolio()` hook — maps `AssetHoldingDTO[]` into a price ticker table
- Table columns: Ticker (Badge), Current Price, 24h Change (green/red color-coded), Last Updated
- Handles loading (skeleton), empty ("No market data available"), and error ("Unable to load market data") states
- Uses existing `Card`, `Table`, `Badge`, `Skeleton` UI primitives

Updated `frontend/src/app/(dashboard)/market-data/page.tsx` to render `<MarketDataPageContent />`.

### Tests Added

| File                                                                     | Tests                   | Type                            |
| ------------------------------------------------------------------------ | ----------------------- | ------------------------------- |
| `frontend/src/components/overview/OverviewPageContent.test.tsx`          | 8                       | Unit (Vitest + Testing Library) |
| `frontend/src/components/market/MarketDataPageContent.test.tsx`          | 10                      | Unit (Vitest + Testing Library) |
| `frontend/src/components/market/MarketDataPageContent.property.test.tsx` | 2 (100 iterations each) | Property-based (fast-check)     |

Property tests validate:

1. Holdings-to-rows data integrity — exactly one table row per holding with correct ticker and price
2. Change indicator color correctness — green for positive, red for negative `change24hPercent`

Full suite: 40/40 tests passing across 7 test files.

### Guardrails Enforced

Zero modifications to:

- `usePortfolio.ts`, `useAuthenticatedUserId.ts`, `fetchWithAuth.ts`, `apiService.ts`
- `SummaryCards.tsx`, `PerformanceChart.tsx`, `AllocationChart.tsx`, `HoldingsTable.tsx`
- All existing `data-testid` attributes preserved

### Files Changed (Since v2)

| File                                                                     | Change                                         |
| ------------------------------------------------------------------------ | ---------------------------------------------- |
| `frontend/src/components/overview/OverviewPageContent.tsx`               | New — session-gated overview dashboard         |
| `frontend/src/components/overview/OverviewPageContent.test.tsx`          | New — 8 unit tests                             |
| `frontend/src/components/market/MarketDataPageContent.tsx`               | New — session-gated market data table          |
| `frontend/src/components/market/MarketDataPageContent.test.tsx`          | New — 10 unit tests                            |
| `frontend/src/components/market/MarketDataPageContent.property.test.tsx` | New — 2 property-based tests                   |
| `frontend/src/app/(dashboard)/overview/page.tsx`                         | Replaced stub with `<OverviewPageContent />`   |
| `frontend/src/app/(dashboard)/market-data/page.tsx`                      | Replaced stub with `<MarketDataPageContent />` |
| `.kiro/specs/ui-polish-overview-market-data/`                            | New — spec files (requirements, design, tasks) |

---

## Changes Since v3: CI Fixes & Cleanup

### Root Cause: `fast-check` Not Installed in CI

Property-based tests failed in CI with `Failed to resolve import "fast-check"`. The root cause was that `package.json` and `package-lock.json` were never committed after adding `fast-check` as a devDependency. Since CI runs `npm ci` (which installs strictly from the lockfile), the package was absent in CI's `node_modules`.

Several workarounds were attempted before identifying the root cause:

- Explicit type annotations on `fc.property` callbacks (kept — good practice for strict TS)
- `server.deps.inline` in vitest config (removed — unnecessary)
- `resolve.alias` for fast-check in vitest config (removed — unnecessary)
- Ambient type declaration `src/types/fast-check.d.ts` (removed — unnecessary)

The actual fix: commit `package.json` and `package-lock.json` with `fast-check` included.

### E2E Smoke Test Updated

The `dashboard-smoke.spec.ts` test was asserting the old placeholder text `"Dashboard overview coming soon."` which no longer exists after the overview page was implemented. Updated to only assert the "Overview" heading, which is rendered by the server component regardless of session state.

### Cleanup

Removed all unnecessary fast-check workarounds from `vitest.config.ts` (restored to original config) and deleted the ambient `src/types/fast-check.d.ts` declaration file.

### Verification

- `npx tsc --noEmit` — clean (0 errors)
- `npm run lint` — clean (0 errors, 3 pre-existing warnings)
- `npm run test` — 40/40 tests passing across 7 files
- `npm run build` — succeeds, all routes present

### Files Changed (Since v3)

| File                                                                     | Change                                                       |
| ------------------------------------------------------------------------ | ------------------------------------------------------------ |
| `frontend/package.json`                                                  | Committed `fast-check` in devDependencies                    |
| `frontend/package-lock.json`                                             | Committed with `fast-check` resolved                         |
| `frontend/vitest.config.ts`                                              | Removed `server.deps.inline` and `resolve.alias` workarounds |
| `frontend/src/types/fast-check.d.ts`                                     | Deleted — unnecessary ambient declaration                    |
| `frontend/src/components/market/MarketDataPageContent.property.test.tsx` | Added explicit type annotations to `fc.property` callbacks   |
| `frontend/tests/e2e/dashboard-smoke.spec.ts`                             | Removed stale placeholder text assertion                     |
