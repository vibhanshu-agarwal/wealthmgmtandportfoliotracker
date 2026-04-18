# CI Fix: Better Auth Requires PostgreSQL in E2E Smoke Job

**Status:** Ready to execute
**Priority:** High
**Date:** 2026-04-11

---

## Root Cause

The Better Auth migration replaced NextAuth's stateless JWT sessions with a database-backed auth system. Better Auth requires PostgreSQL for user lookup (`findUserByEmail`), session creation, and credential verification. The CI `e2e-smoke` job in `frontend-ci.yml` was designed for NextAuth's no-database mode and has no Postgres service container.

When Playwright's `global.setup.ts` hits `POST /api/auth/sign-in/email`, Better Auth tries to connect to `DATABASE_URL` → `localhost:5432` → `ECONNREFUSED`. The sign-in returns 500, setup fails, and all 5 dependent tests are skipped.

## CI Log Evidence

```
[WebServer] Error: connect ECONNREFUSED ::1:5432
[WebServer] Error: connect ECONNREFUSED 127.0.0.1:5432
[WebServer] code: 'ECONNREFUSED'
[setup] Sign-in API response: 500
```

Also:

```
WARN [Better Auth]: Base URL could not be determined.
Please set BETTER_AUTH_URL environment variable.
```

---

## Fix Plan

### File: `.github/workflows/frontend-ci.yml`

#### 1. Update env vars in both jobs (`build-and-test` and `e2e-smoke`)

Replace stale NextAuth vars with Better Auth equivalents:

```yaml
env:
  BETTER_AUTH_SECRET: ci-better-auth-secret-min-32-chars!!
  BETTER_AUTH_URL: http://localhost:3000
  AUTH_JWT_SECRET: ci-jwt-secret-placeholder-min-32-chars!!
  DATABASE_URL: postgresql://wealth_ci:wealth_ci@localhost:5432/wealth_ci
```

Remove:

- `NEXTAUTH_SECRET`
- `AUTH_SECRET`
- `AUTH_URL`

#### 2. Add PostgreSQL service container to `e2e-smoke` job

```yaml
e2e-smoke:
  runs-on: ubuntu-latest
  needs: build-and-test
  services:
    postgres:
      image: postgres:16
      env:
        POSTGRES_DB: wealth_ci
        POSTGRES_USER: wealth_ci
        POSTGRES_PASSWORD: wealth_ci
      ports:
        - 5432:5432
      options: >-
        --health-cmd pg_isready
        --health-interval 10s
        --health-timeout 5s
        --health-retries 5
```

#### 3. Add schema creation step (after Postgres is healthy, before E2E tests)

```yaml
- name: Create Better Auth schema
  run: psql "$DATABASE_URL" -f scripts/better-auth-schema.sql
```

#### 4. Add dev user seed step (after schema, before E2E tests)

```yaml
- name: Seed dev user
  run: npx tsx scripts/seed-dev-user.ts
```

This uses Better Auth's `signUpEmail` API to create the user with proper scrypt password hashing.

#### 5. Update `build-and-test` job env vars

The build job needs `BETTER_AUTH_SECRET` and `DATABASE_URL` for `npm run build` to compile the auth module without errors. Since no actual DB connection happens during build, `DATABASE_URL` can be a dummy value:

```yaml
env:
  BETTER_AUTH_SECRET: ci-better-auth-secret-min-32-chars!!
  BETTER_AUTH_URL: http://localhost:3000
  AUTH_JWT_SECRET: ci-jwt-secret-placeholder-min-32-chars!!
  DATABASE_URL: postgresql://dummy:dummy@localhost:5432/dummy
```

---

## Files to Modify

| File                                | Change                                                         |
| ----------------------------------- | -------------------------------------------------------------- |
| `.github/workflows/frontend-ci.yml` | Add Postgres service, update env vars, add schema + seed steps |

## Files Referenced (no changes needed)

| File                                      | Role                                                                    |
| ----------------------------------------- | ----------------------------------------------------------------------- |
| `frontend/scripts/better-auth-schema.sql` | Creates `ba_user`, `ba_session`, `ba_account`, `ba_verification` tables |
| `frontend/scripts/seed-dev-user.ts`       | Seeds `dev@localhost.local` user via Better Auth API                    |
| `frontend/src/lib/auth.ts`                | Better Auth config — reads `DATABASE_URL` and `BETTER_AUTH_SECRET`      |
| `frontend/tests/e2e/global.setup.ts`      | Playwright auth setup — POSTs to `/api/auth/sign-in/email`              |

---

## Acceptance Criteria

- `frontend-ci.yml` `e2e-smoke` job passes with all E2E tests green
- No `ECONNREFUSED` errors in CI logs
- No "Base URL could not be determined" warnings
- `build-and-test` job continues to pass (lint, typecheck, unit tests, build)
