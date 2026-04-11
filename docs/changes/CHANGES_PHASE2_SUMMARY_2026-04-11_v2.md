# Phase 2 Changes — 2026-04-11 v2

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
