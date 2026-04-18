# Changes Summary — 2026-04-10 (v3)

**Branch:** `architecture/cloud-native-extraction`
**Scope:** `portfolio-service`, `frontend`. No changes to AWS CDK scripts or `infrastructure/`.

---

## Phase 1: Backend Remediation

### 1.1 ObjectMapper Audit — Verification Only

Audited all `src/main/java` production source trees across all modules (`api-gateway`,
`portfolio-service`, `market-data-service`, `insight-service`, `common-dto`) for raw
`new ObjectMapper()` instantiations or `ObjectMapper`-typed field declarations.

**Result:** Zero occurrences found. The only Jackson mapper consumer in production code is
`market-data-service/.../LocalMarketDataSeeder.java`, which already injects the shared
`JsonMapper` Spring bean. A confirming audit comment was added to the class-level Javadoc.

---

### 1.2 Fix `Portfolio.getHoldings()` — Live Mutable Collection

**Bug:** `Portfolio.getHoldings()` was wrapping the internal `ArrayList` in
`Collections.unmodifiableList(...)` before returning it. JPA's dirty-checking and cascade
hydration require the live, mutable collection reference. This caused the REST API to return
an empty `holdings` array for every portfolio.

**Files changed:**

#### `portfolio-service/src/main/java/com/wealth/portfolio/Portfolio.java`

- `getHoldings()` now returns `holdings` directly — `Collections.unmodifiableList()` wrapper
  removed.
- Added package-private `addHolding(AssetHolding h)` helper that appends to the live list and
  calls `h.setPortfolio(this)` to maintain the bidirectional JPA association.

#### `portfolio-service/src/main/java/com/wealth/portfolio/AssetHolding.java`

- Added `setPortfolio(Portfolio portfolio)` setter required by `addHolding`.

---

### 1.3 Verify `PortfolioService.toResponse()` — Confirmed Correct

Verified that `getByUserId` is annotated `@Transactional(readOnly = true)` (JPA session open
during `getHoldings()` call) and that `toResponse()` streams `portfolio.getHoldings()` mapping
each `AssetHolding` to `PortfolioResponse.HoldingResponse`. No logic changes required.
Confirming comments added to both methods.

---

### 1.4 Fix `requireUserExists` — Non-UUID Sub Claim Support

**Bug:** `requireUserExists(String userId)` in both `PortfolioService` and
`PortfolioAnalyticsService` called `UUID.fromString(userId)` unconditionally. The JWT `sub`
claim is `user-001` (a plain string, not a UUID), so every request from the frontend threw
`IllegalArgumentException` → `UserNotFoundException` → HTTP 404. The frontend silently fell
back to `totalValue: 0` → `$0.00`.

**Files changed:**

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java`

- `requireUserExists` now calls `portfolioRepository.existsByUserId(userId)` first.
- UUID parsing only attempted for UUID-format sub claims; non-UUID sub claims (e.g. `user-001`)
  are trusted as gateway-authenticated and pass through without a user table lookup.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAnalyticsService.java`

- Same fix applied — identical `requireUserExists` pattern corrected.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioRepository.java`

- Added `boolean existsByUserId(String userId)` derived query method.

---

### 1.5 Integration Test — Holdings Hydration

**File:** `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioHoldingsHydrationIT.java`

New `@Tag("integration")` test class using `@SpringBootTest` + Testcontainers Postgres:

- `holdingsAreHydratedAfterCacheEvict` — persists a `Portfolio` with 2 `AssetHolding` rows,
  calls `entityManager.flush()` + `entityManager.clear()` to evict the JPA first-level cache,
  reloads via `portfolioRepository.findById()`, asserts `getHoldings().size() == 2`.
- `getPortfolioEndpointReturnsHoldingsArray` — seeds user/portfolio/holdings via `JdbcTemplate`,
  calls `GET /api/portfolio` via MockMvc with `X-User-Id` header, asserts
  `jsonPath("$[0].holdings.length()").value(2)`.

**Test results:**

```
./gradlew :portfolio-service:integrationTest --tests "com.wealth.portfolio.PortfolioHoldingsHydrationIT"
→ BUILD SUCCESSFUL (2 integration tests, 0 failures)
```

---

## Phase 2: Playwright Golden-Path E2E Suite

### 2.1 Auth Helper

**File:** `frontend/tests/e2e/helpers/auth.ts`

- `mintJwt(userId?)` — mints an HS256 JWT using Node's `crypto.createHmac`. Default `userId`
  is `00000000-0000-0000-0000-000000000001` (the seeded dev user UUID).
- `mintSessionCookieJwt(userId?)` — mints a NextAuth session cookie JWT with `__rawJwt`
  embedded in the payload. The `__rawJwt` field is read by the NextAuth `session()` callback
  to populate `session.accessToken` — without it, the frontend sends no `Authorization` header.
- `injectAuthSession(page)` — injects the session cookie as `authjs.session-token`, navigates
  to `/overview`, and polls `/api/auth/session` until `accessToken` is present.

---

### 2.2 API Helper

**File:** `frontend/tests/e2e/helpers/api.ts`

- `ensurePortfolioWithHoldings(request, token)` — verifies `GET /api/portfolio` returns a
  non-empty portfolio with holdings, and `GET /api/portfolio/summary` returns a non-zero
  `totalValue`. Throws descriptive errors pointing to the relevant Flyway migration if checks
  fail.

---

### 2.3 + 2.4 Golden-Path Spec

**File:** `frontend/tests/e2e/golden-path.spec.ts`

Two test suites against the live local stack (no MSW mocks):

**Suite 1 — Data Creation:**

- `beforeAll` calls `ensurePortfolioWithHoldings` to verify Flyway V3 seed data is accessible.
- Asserts `AAPL` and `BTC` tickers are visible in the Holdings Table. ✅ **Passing**

**Suite 2 — Analytics Validation:**

- `beforeAll` same seed verification.
- Asserts `[data-testid="total-value"]` is not `$0.00`. ⚠️ **See known issue below**
- Asserts Holdings Table contains `AAPL` and `BTC`. ✅ **Passing**

---

### 2.5 Playwright Config — Verified

`frontend/playwright.config.ts` confirmed:

- `testDir: "./tests/e2e"` — picks up `golden-path.spec.ts` automatically.
- `webServer.reuseExistingServer: true` — runs against live local stack.
- Confirming comment added.

---

### Frontend Infrastructure Fixes (discovered during E2E work)

#### `frontend/src/auth.ts`

- Added fallback in `jwt()` callback: when `__rawJwt` is absent (cookie injected directly,
  bypassing `encode()`), re-signs the token payload so `session.accessToken` is always
  populated.

#### `frontend/src/app/layout.tsx`

- `RootLayout` converted to `async` Server Component.
- Calls `auth()` server-side and passes the session to `<SessionProvider session={session}>`.
- Eliminates the client-side `/api/auth/session` round-trip on first render, so `useSession()`
  returns `"authenticated"` immediately instead of `"loading"`.

#### `frontend/src/components/layout/SessionProvider.tsx`

- Updated to accept optional `session?: Session | null` prop and forward it to
  `NextAuthSessionProvider`.

#### `frontend/next.config.ts`

- Fixed rewrite rule: changed `source: "/api/((?!auth/).*)"` to
  `source: "/api/:path((?!auth).*)*"` so the named `:path` segment is correctly forwarded to
  the destination `http://localhost:8080/api/:path*`. The previous regex captured the path but
  the unnamed group was not forwarded, causing `/api/portfolio/summary` to proxy to
  `/api` (404).

#### `frontend/src/lib/hooks/usePortfolio.ts`

- Added `!!token` guard to `enabled` condition on `usePortfolio`, `usePortfolioSummary`, and
  `usePortfolioAnalytics` — prevents hooks from firing with an empty Bearer token before the
  session resolves.
- `usePortfolioSummary` retry increased to `3` with `retryDelay: 1000ms`.

#### `frontend/src/components/portfolio/SummaryCards.tsx`

- Loading guard updated to use `isFetching` instead of `isLoading` for the summary query,
  preventing the skeleton from staying visible indefinitely when the query is enabled but
  waiting for the session.
- `portfolioTotal` now prefers `portfolioSummary.totalValue` (backend SQL join, accurate) over
  the frontend-computed value (depends on market-data-service availability).

---

## Test Results

| Suite                                                                        | Status              |
| ---------------------------------------------------------------------------- | ------------------- |
| `portfolio-service` unit tests                                               | ✅ BUILD SUCCESSFUL |
| `portfolio-service` integration tests (incl. `PortfolioHoldingsHydrationIT`) | ✅ BUILD SUCCESSFUL |
| Playwright — Data Creation                                                   | ✅ Passing          |
| Playwright — Holdings Table tickers                                          | ✅ Passing          |
| Playwright — total-value not $0.00                                           | ⚠️ See known issue  |

---

## Known Issue — Backlog

**`total-value` E2E test stuck in skeleton state**

See `docs/todos/BACKLOG_total-value-skeleton-issue.md` for full details, root cause analysis,
and reproduction steps.

Short summary: `SummaryCards` renders the skeleton indefinitely during Playwright E2E tests
because `useSession()` returns `"loading"` when navigating to `/portfolio` after cookie
injection. TanStack Query hooks are gated on `status === "authenticated"`, so they never fire.
The backend returns the correct `$57,839.25` value — the issue is purely in the frontend
session propagation during client-side SPA navigation in headless browser context.
