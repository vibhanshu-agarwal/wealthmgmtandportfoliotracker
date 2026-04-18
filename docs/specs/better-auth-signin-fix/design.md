# Better Auth Sign-In Fix — Bugfix Design

## Overview

Better Auth sign-in and sign-up endpoints return HTTP 500 because the `ba_*` tables (`ba_user`, `ba_session`, `ba_account`, `ba_verification`) do not exist in PostgreSQL. The schema SQL file and dev user seed script exist in `frontend/scripts/` but are never executed during startup. The fix adds a new Flyway migration (`V8__Better_Auth_Schema.sql`) to portfolio-service so the tables are created automatically alongside existing migrations, and integrates the dev user seed into the Docker Compose workflow so authentication works out of the box after `docker compose up`.

## Glossary

- **Bug_Condition (C)**: Any Better Auth API request (sign-in, sign-up, session check) that queries a `ba_*` table — the request fails with HTTP 500 because the table does not exist
- **Property (P)**: After the fix, all Better Auth API requests that query `ba_*` tables succeed (HTTP 200) when given valid input, because the tables exist and contain the expected schema
- **Preservation**: Existing Flyway-managed tables (`users`, `portfolios`, `asset_holdings`, `market_prices`), existing data in the `postgres-data` volume, and the startup behavior of all backend services must remain unchanged
- **`ba_*` tables**: The four PostgreSQL tables Better Auth requires: `ba_user`, `ba_session`, `ba_account`, `ba_verification` — prefixed with `ba_` to avoid conflicts with Flyway-managed tables
- **`auth.ts`**: The Better Auth configuration in `frontend/src/lib/auth.ts` that connects to PostgreSQL via `DATABASE_URL` and maps table names to `ba_` prefixed variants
- **Flyway**: The database migration tool used by portfolio-service — migrations live in `portfolio-service/src/main/resources/db/migration/` and are versioned V1 through V7
- **`seed-dev-user.ts`**: Script at `frontend/scripts/seed-dev-user.ts` that creates a dev user (`dev@localhost.local` / `password`) via Better Auth's `signUpEmail` API

## Bug Details

### Bug Condition

The bug manifests when any HTTP request reaches a Better Auth endpoint that queries PostgreSQL. The `betterAuth` handler in `frontend/src/lib/auth.ts` attempts to query `ba_user` (for sign-in/sign-up), `ba_session` (for session checks), or `ba_account` (for credential lookup). PostgreSQL throws `relation "ba_user" does not exist`, which Better Auth surfaces as an HTTP 500 response.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input of type HTTPRequest
  OUTPUT: boolean

  RETURN input.path STARTS_WITH "/api/auth/"
         AND input.method IN ["POST", "GET"]
         AND requestRequiresTable(input, ["ba_user", "ba_session", "ba_account", "ba_verification"])
         AND NOT tableExists("ba_user", database)
END FUNCTION
```

### Examples

- **Sign-in**: `POST /api/auth/sign-in/email` with `{ email: "dev@localhost.local", password: "password" }` → Expected: HTTP 200 with session cookies. Actual: HTTP 500 `relation "ba_user" does not exist`
- **Sign-up**: `POST /api/auth/sign-up/email` with `{ email: "new@example.com", password: "test123", name: "Test" }` → Expected: HTTP 200 with user details. Actual: HTTP 500 `relation "ba_user" does not exist`
- **Session check**: `GET /api/auth/get-session` with valid session cookie → Expected: HTTP 200 with session data. Actual: HTTP 500 `relation "ba_session" does not exist`
- **Playwright E2E setup**: Global setup calls `POST /api/auth/sign-in/email` → Expected: 200, auth state saved. Actual: 500, all E2E tests fail to run

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- Flyway migrations V1–V7 must continue to run successfully and create/modify `users`, `portfolios`, `asset_holdings`, and `market_prices` tables exactly as before
- Existing data in the `postgres-data` Docker volume must be preserved across restarts — the new migration uses `CREATE TABLE IF NOT EXISTS` and does not drop or truncate any tables
- portfolio-service, market-data-service, insight-service, and api-gateway must start and pass health checks with no changes to their configuration, startup sequence, or database interactions
- Mouse/browser interactions with the frontend dashboard (portfolio views, market data, AI insights) must continue to work as before

**Scope:**
All inputs that do NOT involve Better Auth API endpoints (`/api/auth/*`) should be completely unaffected by this fix. This includes:

- All REST API calls routed through api-gateway to portfolio-service, market-data-service, and insight-service
- Kafka event processing (price updates, insight generation)
- Redis-backed rate limiting in api-gateway
- Frontend data fetching via TanStack Query hooks

## Hypothesized Root Cause

Based on the bug description and codebase analysis, the root cause is a missing deployment step:

1. **No Flyway migration for `ba_*` tables**: The existing Flyway migrations (V1–V7) in `portfolio-service/src/main/resources/db/migration/` only create tables for the portfolio domain (`users`, `portfolios`, `asset_holdings`, `market_prices`). The Better Auth schema SQL exists at `frontend/scripts/better-auth-schema.sql` but is not wired into any automated execution path.

2. **Docker Compose has no init script for `ba_*` tables**: The `postgres` service in `docker-compose.yml` mounts only a named volume (`postgres-data`) — no SQL files are mounted into `/docker-entrypoint-initdb.d/`. Even if they were, Docker's init mechanism only runs on first volume creation, not on subsequent startups.

3. **Dev user seed is never executed**: `frontend/scripts/seed-dev-user.ts` requires the `ba_*` tables to exist before it can create the dev user. Since the tables are never created, the seed script would also fail if run. There is no step in Docker Compose or any startup script that invokes it.

4. **No dependency chain**: There is no mechanism ensuring that (a) tables are created before (b) the dev user is seeded before (c) the frontend starts accepting auth requests. The pieces exist in isolation but are not connected.

## Correctness Properties

Property 1: Bug Condition — Better Auth API requests succeed after schema migration

_For any_ HTTP request to a Better Auth endpoint (`/api/auth/*`) where the request requires querying `ba_*` tables and the input is valid (correct credentials for sign-in, valid data for sign-up), the system SHALL return HTTP 200 with the expected response body (session cookies for sign-in, user details for sign-up), because the `ba_*` tables now exist via the Flyway V8 migration.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Preservation — Existing Flyway migrations and data are unaffected

_For any_ database operation that targets Flyway-managed tables (`users`, `portfolios`, `asset_holdings`, `market_prices`), the fixed system SHALL produce exactly the same behavior as the original system, preserving all existing schema, data, indexes, and constraints. The V8 migration only adds new `ba_*` tables and does not modify, drop, or reference any existing tables.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `portfolio-service/src/main/resources/db/migration/V8__Better_Auth_Schema.sql`

**Change**: Create a new Flyway migration containing the `CREATE TABLE IF NOT EXISTS` statements from `frontend/scripts/better-auth-schema.sql`

**Specific Changes**:

1. **Add V8 Flyway migration**: Copy the contents of `frontend/scripts/better-auth-schema.sql` into a new file `V8__Better_Auth_Schema.sql` in the Flyway migrations directory. This includes:
   - `CREATE TABLE IF NOT EXISTS "ba_user"` with columns: id, name, email, emailVerified, image, createdAt, updatedAt
   - `CREATE TABLE IF NOT EXISTS "ba_session"` with columns: id, expiresAt, token, createdAt, updatedAt, ipAddress, userAgent, userId (FK → ba_user)
   - `CREATE TABLE IF NOT EXISTS "ba_account"` with columns: id, accountId, providerId, userId (FK → ba_user), accessToken, refreshToken, idToken, accessTokenExpiresAt, refreshTokenExpiresAt, scope, password, createdAt, updatedAt
   - `CREATE TABLE IF NOT EXISTS "ba_verification"` with columns: id, identifier, value, expiresAt, createdAt, updatedAt
   - Indexes: `ba_session_userId_idx`, `ba_account_userId_idx`, `ba_verification_identifier_idx`

2. **Add dev user seed migration**: Create `V9__Seed_Better_Auth_Dev_User.sql` that inserts the dev user directly via SQL (using a pre-hashed scrypt password), with an `ON CONFLICT DO NOTHING` clause for idempotency. This avoids needing to run the TypeScript seed script separately.

3. **Update docker-compose.yml** (if needed): No changes required — portfolio-service already depends on postgres and runs Flyway on startup. The V8 and V9 migrations will execute automatically.

4. **Preserve `frontend/scripts/better-auth-schema.sql`**: Keep the existing file as documentation/reference. It remains useful for developers who want to understand the Better Auth schema independently of Flyway.

5. **Preserve `frontend/scripts/seed-dev-user.ts`**: Keep the existing seed script as an alternative manual seeding method. The V9 migration handles automated seeding.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Query PostgreSQL's `information_schema.tables` to confirm `ba_*` tables do not exist, then attempt Better Auth API calls and observe the 500 responses. Run these checks on the UNFIXED code to confirm the root cause.

**Test Cases**:

1. **Table existence check**: Query `SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'ba_%'` — expect empty result set (will confirm missing tables on unfixed code)
2. **Sign-in failure**: `POST /api/auth/sign-in/email` with valid dev credentials — expect HTTP 500 with "relation does not exist" error (will fail on unfixed code)
3. **Sign-up failure**: `POST /api/auth/sign-up/email` with new user data — expect HTTP 500 (will fail on unfixed code)
4. **Flyway migration list**: Query `flyway_schema_history` to confirm only V1–V7 exist — no V8 migration present (will confirm on unfixed code)

**Expected Counterexamples**:

- All Better Auth API calls return HTTP 500 with PostgreSQL error "relation ba_user does not exist"
- Possible causes confirmed: no Flyway migration for ba\_\* tables, no Docker init script, no automated seed execution

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**

```
FOR ALL input WHERE isBugCondition(input) DO
  -- After V8 migration runs, tables exist
  result := betterAuthHandler(input)
  ASSERT result.status = 200
  ASSERT result.body CONTAINS expected_auth_response
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalSystem(input) = fixedSystem(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for non-auth API calls and database operations, then write property-based tests capturing that behavior.

**Test Cases**:

1. **Flyway migration preservation**: Run all migrations (V1–V8) and verify `users`, `portfolios`, `asset_holdings`, `market_prices` tables have identical schema to V1–V7 only
2. **Existing data preservation**: Insert test data into Flyway-managed tables, run V8 migration, verify data is unchanged
3. **Service health preservation**: Start portfolio-service with V8 migration and verify health check passes at `/actuator/health/readiness`
4. **Schema idempotency**: Run V8 migration on a database where `ba_*` tables already exist (via `CREATE TABLE IF NOT EXISTS`) — verify no errors

### Unit Tests

- Verify V8 migration SQL is syntactically valid and creates all four `ba_*` tables with correct columns and constraints
- Verify V9 seed migration inserts dev user with correct email and `ON CONFLICT DO NOTHING` behavior
- Verify `CREATE TABLE IF NOT EXISTS` does not error when tables already exist
- Verify foreign key constraints between `ba_session` → `ba_user` and `ba_account` → `ba_user`

### Property-Based Tests

- Generate random sequences of Flyway migrations (V1–V8) and verify all tables exist with correct schema after full migration
- Generate random Better Auth API requests (sign-in, sign-up, session check) with valid inputs and verify HTTP 200 responses after migration
- Generate random non-auth API requests and verify responses are identical before and after V8 migration

### Integration Tests

- Full Docker Compose startup: `docker compose up`, wait for portfolio-service health check, then verify `ba_*` tables exist via direct PostgreSQL query
- End-to-end auth flow: After startup, `POST /api/auth/sign-in/email` with dev credentials → verify HTTP 200 and session cookies
- Playwright global setup: Run the E2E global setup and verify it completes successfully, saving auth state
- Restart preservation: Insert data, restart postgres container, verify all data (both Flyway-managed and `ba_*` tables) persists
