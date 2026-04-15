# Phase 3 Changes — 2026-04-15 v2

## Post-Commit Cleanup and Better Auth Bugfix Commit

Cleans up stale test mocks left over from the BFF JWT token exchange refactor, removes auto-generated test artifacts from version control, and commits the previously uncommitted Better Auth sign-in bugfix (Flyway V8+V9 migrations, integration tests, spec, and requirements PDF).

Prior changes: [CHANGES_PHASE3_SUMMARY_2026-04-15_v1.md](./CHANGES_PHASE3_SUMMARY_2026-04-15_v1.md)

---

## Summary

### 1. Stale Jose Mock Removal (`insights-actions.test.ts`)

After the `mintToken` refactor (v1), `fetchWithAuth.server.ts` no longer imports `jose` directly — it imports `mintToken`. The insights action test still had a `vi.mock("jose", ...)` block targeting the old import chain, plus an unnecessary `vi.stubEnv("AUTH_JWT_SECRET", ...)` in `beforeEach`.

- Replaced `vi.mock("jose", ...)` with `vi.mock("@/lib/auth/mintToken", ...)` returning a deterministic fake JWT
- Removed the `vi.stubEnv("AUTH_JWT_SECRET", ...)` call (no longer needed since the test mocks `mintToken` directly, not the underlying signing)
- All 4 existing tests pass unchanged

### 2. Test Results Removed from Version Control

`frontend/test-results/.last-run.json` was tracked by git — an auto-generated Vitest artifact that should never be committed.

- Ran `git rm --cached -r frontend/test-results/` to untrack without deleting local files
- Added `/test-results` to `frontend/.gitignore` under the testing section

### 3. Better Auth Flyway Migrations (V8 + V9)

These migrations were created during the Better Auth sign-in bugfix spec but had not been committed to the `architecture/cloud-native-extraction` branch.

**V8 — Better Auth Schema** (`V8__Better_Auth_Schema.sql`):

- Creates four tables: `ba_user`, `ba_session`, `ba_account`, `ba_verification`
- Creates three indexes: `ba_session_userId_idx`, `ba_account_userId_idx`, `ba_verification_identifier_idx`
- All statements use `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` for idempotency
- Only adds new tables — does not modify existing Flyway-managed tables (`users`, `portfolios`, `asset_holdings`, `market_prices`)

**V9 — Dev User Seed** (`V9__Seed_Better_Auth_Dev_User.sql`):

- Inserts dev user (`dev@localhost.local` / `password`) into `ba_user` with UUID `00000000-0000-0000-0000-000000000001`
- Inserts credential account into `ba_account` with pre-computed scrypt hash (N=16384, r=16, p=1, dkLen=64)
- Uses `ON CONFLICT DO NOTHING` for idempotency on repeated `docker compose up` cycles

### 4. Better Auth Integration Tests

**BetterAuthSchemaExplorationTest.java** (5 tests):

- Table existence — all four `ba_*` tables exist after migrations
- Schema correctness — `ba_user` has expected columns (`id`, `name`, `email`, `emailVerified`, `image`, `createdAt`, `updatedAt`)
- Index existence — all three Better Auth indexes present
- Foreign key constraints — `ba_session.userId` and `ba_account.userId` reference `ba_user.id`
- Dev user seed — `dev@localhost.local` exists in `ba_user`

**FlywayPreservationTest.java** (5 parameterized tests):

- Existing table schema preservation — `users`, `portfolios`, `asset_holdings`, `market_prices` retain expected columns and types
- Existing data preservation — test rows inserted before V8 survive intact
- Flyway history preservation — V1–V7 entries present with `success = true`
- Idempotency — table count unchanged after repeated migration check
- No cross-table interference — no `ba_*` columns, constraints, or indexes on existing tables

Both test classes use `@Tag("integration")`, `@Testcontainers`, and `@SpringBootTest` with PostgreSQL 16 Alpine.

### 5. Spec and Documentation

- Committed `better-auth-signin-fix/tasks.md` — all 4 tasks marked complete
- Committed `BFF_HS256_JWT_Token_Exchange_Plan.pdf` — original requirements document for the JWT token exchange feature

---

## Files Changed

### Commit 1: `chore: clean up stale jose mock, gitignore test-results, add changelog`

| File                                                   | Change                                                  |
| ------------------------------------------------------ | ------------------------------------------------------- |
| `frontend/src/lib/api/insights-actions.test.ts`        | Modified — replaced stale jose mock with mintToken mock |
| `frontend/.gitignore`                                  | Modified — added `/test-results`                        |
| `frontend/test-results/.last-run.json`                 | Removed from tracking                                   |
| `docs/changes/CHANGES_PHASE3_SUMMARY_2026-04-15_v1.md` | New — BFF JWT token exchange changelog                  |

### Commit 2: `fix(portfolio-service): add Better Auth Flyway migrations V8+V9`

| File                                                                                  | Change                                  |
| ------------------------------------------------------------------------------------- | --------------------------------------- |
| `portfolio-service/src/main/resources/db/migration/V8__Better_Auth_Schema.sql`        | New — Better Auth tables and indexes    |
| `portfolio-service/src/main/resources/db/migration/V9__Seed_Better_Auth_Dev_User.sql` | New — Dev user seed with scrypt hash    |
| `portfolio-service/src/test/java/.../BetterAuthSchemaExplorationTest.java`            | New — 5 bug condition exploration tests |
| `portfolio-service/src/test/java/.../FlywayPreservationTest.java`                     | New — 5 preservation property tests     |
| `.kiro/specs/better-auth-signin-fix/tasks.md`                                         | New — completed bugfix spec tasks       |
| `docs/agent-instructions/BFF_HS256_JWT_Token_Exchange_Plan.pdf`                       | New — requirements PDF                  |

---

## Verification

- `npm run test -- --run src/lib/api/insights-actions.test.ts` → 4 tests passed
- `git status --short` → clean working tree after push
- Both commits pushed to `architecture/cloud-native-extraction` on GitHub
