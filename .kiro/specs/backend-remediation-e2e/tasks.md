# Implementation Plan

## Phase 1: Backend Remediation

- [x] 1.1 Audit ObjectMapper usages in all production source files
      Search every `src/main/java` tree across all modules for `new ObjectMapper()` or field
      declarations typed as `ObjectMapper`. Confirm zero occurrences remain in production code.
      If any are found, replace with `JsonMapper.builder().build()` or inject the existing
      `JsonMapper` Spring bean. Document findings in a brief inline comment.
      _Verification only — the codebase is expected to already be clean._

- [x] 1.2 Fix `Portfolio.getHoldings()` to return the live mutable collection
      In `portfolio-service/src/main/java/com/wealth/portfolio/Portfolio.java`:
  - Confirm the `holdings` field is declared as `List<AssetHolding> holdings = new ArrayList<>()`.
  - Change `getHoldings()` to `return holdings;` (remove the `Collections.unmodifiableList()`
    wrapper). JPA requires the live reference for dirty-checking and cascade hydration.
  - Add a package-private `addHolding(AssetHolding h)` helper that appends to the live list and
    sets the back-reference (`h.setPortfolio(this)` if needed), so service-layer code never
    manipulates the collection directly.

- [x] 1.3 Verify `PortfolioService.toResponse()` iterates the hydrated collection
      In `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java`:
  - Confirm `getByUserId` is annotated `@Transactional(readOnly = true)` so the session is open
    when `getHoldings()` is called on each entity.
  - Confirm `toResponse()` streams `portfolio.getHoldings()` and maps each `AssetHolding` to a
    `PortfolioResponse.HoldingResponse`. No changes needed if already correct; add a comment
    confirming the fix is in place.

- [x] 1.4 Add a `@Transactional` integration test for the holdings hydration fix
      In `portfolio-service/src/test/java/com/wealth/portfolio/`:
  - Create `PortfolioHoldingsHydrationIT.java` annotated `@Tag("integration")`.
  - Use `@SpringBootTest` + Testcontainers Postgres to:
    1. Persist a `Portfolio` with two `AssetHolding` rows via the repository.
    2. Evict the entity from the JPA first-level cache (call `entityManager.clear()`).
    3. Reload via `portfolioRepository.findById(...)`.
    4. Assert `portfolio.getHoldings().size() == 2`.
  - Also call `GET /api/portfolio` via `MockMvc` (with a stubbed `X-User-Id` header) and assert
    the JSON response contains a `holdings` array with 2 elements.

---

## Phase 2: Playwright Golden-Path E2E Suite

- [x] 2.1 Create the Auth Helper utility
      Create `frontend/tests/e2e/helpers/auth.ts`:
  - Export `async function injectAuthSession(page: Page): Promise<void>`.
  - Mint an HS256 JWT for `user-001` using Node's built-in `crypto.createHmac` (same pattern as
    `dashboard-data.spec.ts`). Read the secret from `process.env.AUTH_JWT_SECRET` with the same
    default fallback.
  - Use `page.context().addCookies(...)` or `page.evaluate(...)` to inject the NextAuth session
    token so the app treats the browser as authenticated. Inspect the existing NextAuth cookie
    name (`next-auth.session-token` or `__Secure-next-auth.session-token`) from
    `frontend/src/auth.ts` to use the correct key.
  - After injection, navigate to `/overview` and assert the heading is visible to confirm the
    session is active before returning.

- [x] 2.2 Create the backend API helper for test data setup
      Create `frontend/tests/e2e/helpers/api.ts`:
  - Export `async function ensurePortfolioWithHoldings(request: APIRequestContext, token: string): Promise<string>`
    that:
    1. Calls `GET http://localhost:8080/api/portfolio` with `Authorization: Bearer <token>`.
    2. If no portfolio exists, calls `POST http://localhost:8080/api/portfolio` to create one
       (adjust endpoint to match actual backend route — check `PortfolioController`).
    3. Calls the holdings endpoint to add `AAPL` (quantity `10`) and `BTC` (quantity `0.5`) if
       they are not already present.
    4. Returns the portfolio ID.
  - Export `function mintJwt(userId?: string): string` (extracted/shared from
    `dashboard-data.spec.ts` to avoid duplication).

- [x] 2.3 Implement `golden-path.spec.ts` — Data Creation test
      Create `frontend/tests/e2e/golden-path.spec.ts`:
  - `test.describe("Golden Path — Data Creation", ...)`:
    - `test.beforeAll`: call `ensurePortfolioWithHoldings` via `request` context to seed `AAPL`
      and `BTC` holdings directly through the backend API (no UI form interaction required if the
      portfolio management UI does not yet exist).
    - `test("portfolio holdings are persisted and returned by the API")`:
      1. Inject auth session via `injectAuthSession(page)`.
      2. Navigate to `/portfolio`.
      3. Use `expect(page.getByText("AAPL")).toBeVisible()` to assert the ticker appears in the
         Holdings Table.
      4. Use `expect(page.getByText("BTC")).toBeVisible()` to assert the second ticker appears.

- [x] 2.4 Implement `golden-path.spec.ts` — Analytics Validation test
      Append to `frontend/tests/e2e/golden-path.spec.ts`:
  - `test.describe("Golden Path — Analytics Validation", ...)`:
    - `test.beforeAll`: same `ensurePortfolioWithHoldings` seed (idempotent — safe to call again).
    - `test("total-value is not $0.00 after holdings are seeded")`:
      1. Inject auth session via `injectAuthSession(page)`.
      2. Navigate to `/portfolio`.
      3. `await expect(page.getByTestId("total-value")).not.toHaveText("$0.00")` — relies on
         Playwright auto-waiting; no explicit `waitForTimeout`.
    - `test("holdings table contains AAPL and BTC tickers")`:
      1. Inject auth session.
      2. Navigate to `/portfolio`.
      3. Assert `page.getByText("AAPL")` is visible.
      4. Assert `page.getByText("BTC")` is visible.

- [x] 2.5 Verify Playwright config covers the new spec
      In `frontend/playwright.config.ts`:
  - Confirm `testDir` is `"./tests/e2e"` (already set) so `golden-path.spec.ts` is picked up
    automatically.
  - Confirm `webServer.reuseExistingServer: true` is set so the suite can run against an already-
    running Next.js instance during local development.
  - No changes expected; add a comment if the config is already correct.

- [x] 2.6 Run the full E2E suite and confirm all tests pass
      With Docker Compose infrastructure up and both the Next.js server and Spring Boot services
      running locally:
  ```bash
  cd frontend
  npm run test:e2e
  ```
  All tests in `golden-path.spec.ts` must pass. Fix any selector mismatches, auth injection
  issues, or backend endpoint discrepancies discovered during the run.
