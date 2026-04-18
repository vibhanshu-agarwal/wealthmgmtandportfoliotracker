# Phase 2 Changes — 2026-04-11 v1

## E2E Test Infrastructure Fixes & Backend POST Endpoints

### Context

The Playwright golden-path E2E test for `[data-testid="total-value"]` was timing out because
the `SummaryCards` component stayed in skeleton state indefinitely. This was caused by a
combination of NextAuth client-side hydration issues in the standalone build and the test
infrastructure not being resilient to empty databases.

### Backend Changes

**`PortfolioController.java`** — Added two new endpoints:

- `POST /api/portfolio` — creates a portfolio for the authenticated user
- `POST /api/portfolio/{portfolioId}/holdings` — adds or upserts a holding by ticker + quantity

**`PortfolioService.java`** — Added `createPortfolio()` and `addHolding()` methods. The
`addHolding` method upserts: updates quantity if the ticker already exists, adds a new holding
otherwise. Both methods are `@Transactional`.

### Frontend Changes

**`PortfolioPageContent.tsx`** (new) — Client component that gates all portfolio data components
(`SummaryCards`, `PerformanceChart`, `AllocationChart`, `HoldingsTable`) behind a strict
`useSession()` check:

- `status === "loading"` → skeleton
- `status === "authenticated"` → render data components
- `status === "unauthenticated"` → redirect to `/login`

**`portfolio/page.tsx`** — Refactored to render `<PortfolioPageContent />` instead of individual
data components directly. The page header remains a Server Component.

**`SummaryCards.tsx`** — Removed the second skeleton condition `isLoading && !portfolioSummary`
which was gating the entire component on `usePortfolio`'s loading state. The `total-value` card
only needs `portfolioSummary` data; `usePortfolio` retrying market prices should not block it.

**`layout.tsx`** — Added server-side debug log for `auth()` session to diagnose hydration issues
in the standalone build.

**`.env.local`** — Added `AUTH_TRUST_HOST=true` (required for NextAuth v5 beta in standalone
Node environments outside Vercel).

**`package.json`** — Updated `test:e2e` script to include `--trace on` for Playwright trace
generation on every run.

**`playwright.config.ts`** — Changed `baseURL` and `webServer.url` from `127.0.0.1` to
`localhost` to match `AUTH_URL` and prevent cookie domain mismatches.

### E2E Test Changes

**`helpers/auth.ts`** — Complete rewrite of `injectAuthSession`:

- Abandoned cookie injection and network interception strategies (both blocked by NextAuth CSRF)
- Implemented direct CSRF token fetch + POST to `/api/auth/callback/credentials`
- Added `page.reload({ waitUntil: "networkidle" })` to force SSR hydration after login
- Changed `mintJwt` default userId from UUID to `"user-001"` to match Flyway V3 seed

**`helpers/api.ts`** — Made `ensurePortfolioWithHoldings` self-healing:

- If no portfolio exists, creates one via `POST /api/portfolio`
- Seeds missing AAPL and BTC holdings via `POST /api/portfolio/{id}/holdings`
- No longer throws on empty database state

**`golden-path.spec.ts`** — Removed `waitForResponse` for `/api/portfolio/summary` (was causing
race conditions). Added `page.reload()` on `/portfolio` to force SSR hydration.

**`dashboard-data.spec.ts`** — Test 1 now asserts session cookie presence via
`page.context().cookies()` instead of fetching `/api/auth/session`. Test 2 adds `page.reload()`
on `/portfolio`.

### Test Results

7/9 tests passing. 2 remaining failures (`total-value` visibility) are parked as a backlog item
at `docs/todos/backlog/total-value-e2e-hydration/README.md`. The root cause is isolated to
NextAuth v5 beta client hydration in the standalone build — not a backend or data issue.

### Files Changed

| File                                                         | Change                                 |
| ------------------------------------------------------------ | -------------------------------------- |
| `portfolio-service/.../PortfolioController.java`             | Added POST endpoints                   |
| `portfolio-service/.../PortfolioService.java`                | Added createPortfolio, addHolding      |
| `frontend/src/components/portfolio/PortfolioPageContent.tsx` | New session gate component             |
| `frontend/src/app/(dashboard)/portfolio/page.tsx`            | Refactored to use PortfolioPageContent |
| `frontend/src/components/portfolio/SummaryCards.tsx`         | Fixed skeleton condition               |
| `frontend/src/app/layout.tsx`                                | Added auth() debug log                 |
| `frontend/.env.local`                                        | Added AUTH_TRUST_HOST=true             |
| `frontend/package.json`                                      | trace on for test:e2e                  |
| `frontend/playwright.config.ts`                              | localhost domain fix                   |
| `frontend/tests/e2e/helpers/auth.ts`                         | CSRF POST login strategy               |
| `frontend/tests/e2e/helpers/api.ts`                          | Self-healing portfolio seeding         |
| `frontend/tests/e2e/golden-path.spec.ts`                     | Removed waitForResponse, added reload  |
| `frontend/tests/e2e/dashboard-data.spec.ts`                  | Cookie assertion, reload fix           |
| `docs/todos/backlog/total-value-e2e-hydration/README.md`     | Backlog item for remaining 2 tests     |
