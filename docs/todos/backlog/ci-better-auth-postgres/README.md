# CI Fix: Better Auth Requires PostgreSQL in E2E Smoke Job

**Status:** Superseded — no longer applicable (2026-08-12)
**Priority:** ~~High~~
**Date:** 2026-04-11

---

## Superseded

The premise of this fix plan no longer exists. `new-user-signup-profile`
([#85](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/85))
fully retired Better Auth: `V16__Drop_Better_Auth_Tables.sql` dropped the
`ba_*` tables, and Task 7 deleted `frontend/scripts/better-auth-schema.sql`,
`frontend/scripts/seed-dev-user.ts`, and `frontend/src/lib/auth.ts` — every
file this plan's "Files to Modify" / "Files Referenced" sections name.

Separately, `e2e-smoke` in `frontend-ci.yml` (the job this plan targeted) no
longer runs any auth flow at all: it now runs only the `static-smoke`
Playwright project, which checks the static export is served and the login
route returns HTML — no gateway, no database, nothing to seed. Gateway
login is covered elsewhere (`frontend-e2e-integration.yml`, against real
per-user auth via `/api/auth/login`). So the "Add PostgreSQL service
container to e2e-smoke" fix this plan proposed is now moot on top of being
inapplicable — that job doesn't touch auth or a database at either end.

The vestigial `BETTER_AUTH_SECRET` / `BETTER_AUTH_URL` / `DATABASE_URL` env
vars this plan added to `build-and-test` (proven unread by any code — not
`src/`, not the Vitest suite, not the Next.js build) were removed in the
same change that added this note.

Left in place, unexecuted, as a historical record — do not action.

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
