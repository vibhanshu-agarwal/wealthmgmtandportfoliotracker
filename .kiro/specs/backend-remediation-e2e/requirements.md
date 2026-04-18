# Requirements Document

## Introduction

Two subtle logical bugs have been identified in the backend that were not caught by existing unit tests.
This feature spec covers their remediation and the immediate introduction of a Playwright End-to-End
(E2E) "golden path" test suite that exercises the live local stack — no mocks — to prevent regressions
and prove that real data can be created, persisted, and read end-to-end.

**Phase 1 — Backend Remediation**

1. Audit every production class for raw `ObjectMapper` instantiation and confirm all usages have been
   migrated to `JsonMapper.builder().build()` as preferred by Spring Boot 4.
2. Fix `Portfolio.java`: `getHoldings()` currently wraps the internal `ArrayList` in
   `Collections.unmodifiableList(...)` before returning it to callers. JPA's dirty-checking and
   cascade operations require the live, mutable collection reference. The fix must ensure the
   collection is initialised as a mutable `ArrayList`, that `getHoldings()` returns the live
   reference, and that the `PortfolioService` DTO mapper correctly iterates the hydrated list so
   the REST API returns actual holdings instead of an empty array.

**Phase 2 — Playwright Golden-Path E2E Suite**

A new spec file `frontend/tests/e2e/golden-path.spec.ts` must exercise the full create → persist →
read cycle against the live local backend (Next.js dev/standalone + Spring Boot + Docker Compose
infrastructure). The suite must not use MSW mocks.

---

## Glossary

- **Portfolio_Service**: The Spring Boot microservice at `portfolio-service/` that owns portfolio and
  holding persistence via JPA + PostgreSQL.
- **API_Gateway**: The Spring Cloud Gateway at `api-gateway/` that validates JWTs and forwards
  requests to downstream services.
- **Frontend**: The Next.js 16 application at `frontend/` that renders the dashboard UI.
- **Playwright_Suite**: The Playwright test runner and the spec files under `frontend/tests/e2e/`.
- **Auth_Helper**: A TypeScript utility used by the Playwright_Suite to inject a pre-minted HS256 JWT
  directly into the browser storage context, bypassing the NextAuth login UI.
- **Golden_Path**: The happy-path user journey: authenticate → create portfolio → add holdings →
  verify dashboard reflects real data.
- **JsonMapper**: `com.fasterxml.jackson.databind.json.JsonMapper`, the Spring Boot 4 preferred
  replacement for `ObjectMapper`.
- **Holdings_Collection**: The `List<AssetHolding>` field on `Portfolio.java` managed by JPA.
- **data-testid**: A `data-testid` HTML attribute used as a stable Playwright selector that is
  independent of CSS class names or text content.

---

## Requirements

### Requirement 1: ObjectMapper Audit and Verification

**User Story:** As a developer, I want to confirm that no production class instantiates a raw
`ObjectMapper`, so that all JSON serialisation uses the Spring Boot 4 preferred `JsonMapper` with
consistent configuration.

#### Acceptance Criteria

1. THE Portfolio_Service SHALL contain zero usages of `new ObjectMapper()` in any production source
   file under `src/main/java/`.
2. WHEN a production class requires a Jackson mapper, THE Portfolio_Service SHALL obtain it as a
   `JsonMapper` instance constructed via `JsonMapper.builder().build()` or injected as a Spring bean.
3. THE Portfolio_Service SHALL pass all existing unit and integration tests after the audit with no
   test failures introduced by the verification step.

---

### Requirement 2: Portfolio Holdings Collection Fix

**User Story:** As a developer, I want `Portfolio.getHoldings()` to return the live mutable
collection, so that JPA can hydrate it from the database and the REST API returns actual holdings
data instead of an empty list.

#### Acceptance Criteria

1. THE Portfolio_Service SHALL initialise the `holdings` field in `Portfolio.java` as
   `new ArrayList<>()` (mutable).
2. WHEN JPA loads a `Portfolio` entity from the database, THE Portfolio_Service SHALL hydrate the
   `holdings` collection with all associated `AssetHolding` rows via the existing `@OneToMany`
   mapping.
3. THE Portfolio_Service SHALL expose `getHoldings()` returning the live collection reference so
   that JPA dirty-checking and cascade operations function correctly.
4. WHEN `PortfolioService.toResponse()` maps a `Portfolio` to a `PortfolioResponse`, THE
   Portfolio_Service SHALL iterate the hydrated `holdings` collection and include all holdings in
   the response payload.
5. WHEN `GET /api/portfolio` is called for a user who has holdings, THE Portfolio_Service SHALL
   return a JSON array where each portfolio object contains a non-empty `holdings` array.
6. IF a `Portfolio` entity has no associated `AssetHolding` rows, THEN THE Portfolio_Service SHALL
   return an empty `holdings` array (not null) in the response.

---

### Requirement 3: Playwright Auth Helper

**User Story:** As a QA engineer, I want a reusable auth helper that injects a valid JWT into the
browser context programmatically, so that E2E tests skip the login UI and run fast.

#### Acceptance Criteria

1. THE Auth_Helper SHALL mint a valid HS256 JWT for `user-001` using the secret defined in
   `AUTH_JWT_SECRET` (defaulting to `local-dev-secret-change-me-min-32-chars`).
2. THE Auth_Helper SHALL inject the minted token into the browser's `localStorage` or `sessionStorage`
   in a format that the Next.js `next-auth` session provider recognises as an authenticated session.
3. WHEN the Auth_Helper has completed injection, THE Playwright_Suite SHALL be able to navigate
   directly to any protected dashboard route without being redirected to `/login`.
4. THE Auth_Helper SHALL be implemented as a reusable TypeScript function importable by any spec
   file in `frontend/tests/e2e/`.

---

### Requirement 4: E2E Data Creation Test

**User Story:** As a QA engineer, I want an E2E test that creates a portfolio and adds holdings via
the UI, so that I can verify the full write path from browser to database.

#### Acceptance Criteria

1. WHEN the Data_Creation test runs, THE Playwright_Suite SHALL authenticate as `user-001` using the
   Auth_Helper without interacting with the login form.
2. THE Playwright_Suite SHALL navigate to the portfolio management UI and create a new portfolio if
   one does not already exist.
3. THE Playwright_Suite SHALL add a holding for ticker `AAPL` with a specified quantity via the
   portfolio management UI form.
4. THE Playwright_Suite SHALL add a second holding for ticker `BTC` with a specified quantity via
   the portfolio management UI form.
5. WHEN both holdings have been submitted, THE Playwright_Suite SHALL receive a success confirmation
   from the UI (e.g. a success toast, updated holdings list, or navigation to the portfolio view).
6. IF the portfolio management UI does not yet exist, THEN THE Playwright_Suite SHALL create the
   holdings by calling the backend REST API directly via `request` context and then reload the
   portfolio page to verify persistence.

---

### Requirement 5: E2E Analytics Validation Test

**User Story:** As a QA engineer, I want an E2E test that asserts the dashboard reflects real
persisted data, so that I can detect regressions in the data pipeline from database to UI.

#### Acceptance Criteria

1. WHEN the Analytics_Validation test runs, THE Playwright_Suite SHALL authenticate as `user-001`
   using the Auth_Helper.
2. THE Playwright_Suite SHALL navigate to the portfolio dashboard page that renders the
   `[data-testid="total-value"]` element.
3. WHEN the page has finished loading, THE Playwright_Suite SHALL assert that
   `[data-testid="total-value"]` does NOT display `$0.00` using Playwright's built-in auto-waiting
   `expect(...).not.toHaveText("$0.00")`.
4. THE Playwright_Suite SHALL assert that the Holdings Table contains a row with the ticker `AAPL`
   that was created in the Data_Creation test.
5. THE Playwright_Suite SHALL assert that the Holdings Table contains a row with the ticker `BTC`
   that was created in the Data_Creation test.
6. WHILE TanStack Query is in a loading state, THE Playwright_Suite SHALL rely on Playwright's
   default auto-waiting rather than explicit `waitForTimeout` calls, so that tests are resilient to
   variable network latency.

---

### Requirement 6: E2E Test Isolation and Infrastructure

**User Story:** As a developer, I want the E2E suite to run against the live local stack without
MSW mocks, so that tests catch real integration failures rather than mock-induced false positives.

#### Acceptance Criteria

1. THE Playwright_Suite SHALL NOT import or activate any MSW handler during E2E test execution.
2. THE Playwright_Suite SHALL target the Next.js server at `http://127.0.0.1:3000` and the
   API_Gateway at `http://localhost:8080` as defined in `playwright.config.ts`.
3. WHERE the `golden-path.spec.ts` suite requires test data to be present before assertions, THE
   Playwright_Suite SHALL use a `test.beforeAll` or `test.beforeEach` hook to set up that data via
   the backend API using Playwright's `request` context.
4. THE Playwright_Suite SHALL be runnable via the existing `npm run test:e2e` script defined in
   `frontend/package.json` without additional configuration.
5. THE Playwright_Suite SHALL place the golden-path spec at
   `frontend/tests/e2e/golden-path.spec.ts`, consistent with the existing test directory convention.
