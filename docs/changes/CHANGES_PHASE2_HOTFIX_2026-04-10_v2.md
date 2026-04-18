# Hotfix — 2026-04-10

Branch: `architecture/cloud-native-extraction`

---

## Issues Fixed

### 1. Next.js 16 middleware deprecation

`frontend/src/middleware.ts` renamed to `frontend/src/proxy.ts` per the Next.js 16 file convention change. The old name produced a deprecation warning and will be unsupported in future releases.

### 2. `/api/auth/session` returning 404

`next.config.ts` rewrite rule `source: "/api/:path*"` was proxying all `/api/*` traffic to the Spring Boot backend (port 8080), including NextAuth's own internal routes (`/api/auth/*`). The backend has no such routes, so every session check returned 404 and the browser logged `ClientFetchError`. Fixed by narrowing the rewrite to exclude `/api/auth/**`.

### 3. Auth.js v5 environment variable naming

`.env.local` used `NEXTAUTH_SECRET` (v4 name) instead of `AUTH_SECRET` (v5 requirement). Added `AUTH_URL=http://localhost:3000` which Auth.js v5 also requires for CSRF and callback URL resolution.

### 4. Login page was a placeholder

`frontend/src/app/(auth)/login/page.tsx` contained only a static "Authentication coming in Phase 2" message with no form. Unauthenticated users were redirected to `/login` by the proxy but had no way to sign in. Replaced with a working credentials form. Local dev credentials: `user-001` / `password`.

### 5. UserMenu showing hardcoded "Alex Morgan"

`UserMenu.tsx` used a `MOCK_USER` constant. Replaced with `useSession()` to display the real authenticated user's name and email. Sign-out button now calls `signOut({ callbackUrl: "/login" })`. Profile, Settings, and Notifications menu items now navigate to `/settings`.

### 6. Spring `@Profile("local")` beans not registering

All three backend services (`portfolio-service`, `market-data-service`, `api-gateway`) had `@Profile("local")`-annotated beans but no default active profile configured, so Spring never activated the local profile and the beans were skipped. This caused a hard startup failure on `portfolio-service`:

```
Parameter 2 of constructor in PortfolioAnalyticsService required a bean of type
'com.wealth.portfolio.FxRateProvider' that could not be found.
```

Fixed by adding `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:local}` to each service's `application.yml`. The `aws` profile can be activated at deploy time via the `SPRING_PROFILES_ACTIVE` environment variable.

### 7. Portfolio page hard error states when backend is unreachable

All four portfolio components (`SummaryCards`, `PerformanceChart`, `AllocationChart`, `HoldingsTable`) showed red error messages when the backend was down. Changed to graceful degradation:

- `SummaryCards` renders zero values instead of an error card
- `PerformanceChart` falls back to the existing synthetic performance series (already implemented in `portfolio.ts`) when the analytics endpoint is unavailable
- `AllocationChart` and `HoldingsTable` show neutral empty states

### 8. CI/CD build failure — stale `days` prop on `PerformanceChart`

`portfolio/page.tsx` was passing `days={30}` to `<PerformanceChart />`, but the prop was removed during the hotfix refactor (the component now sources period data internally via `usePortfolioPerformance`). TypeScript correctly rejected this at build time with `TS2322`. Removed the stale prop.

### 9. CI/CD build failure — Dockerfile secrets in build args

The `frontend/Dockerfile` builder stage was injecting `NEXTAUTH_SECRET` and `AUTH_JWT_SECRET` via `ARG`/`ENV`, which:

- Triggered Docker secrets-in-build-arg warnings in CI
- Used the old `NEXTAUTH_SECRET` name (v4) instead of `AUTH_SECRET` (v5)
- Was unnecessary — these are runtime secrets, not build-time

Removed the `ARG`/`ENV` lines. Secrets must be injected at runtime via the container orchestrator (ECS task definition, `docker run -e`, etc.).

### 10. CI/CD build failure — `proxy.ts` export not recognised as a function

Next.js 16's build validator requires `proxy.ts` to export a callable function via `export default` or a named `proxy` export. The `export { auth as default } from "@/auth"` re-export form was not recognised, causing:

```
The file "./src/proxy.ts" must export a function, either as a default export or as a named "proxy" export.
```

Fixed by switching to an explicit import + `export default auth` pattern.

---

## Files Changed

| File                                                     | Change                                                       |
| -------------------------------------------------------- | ------------------------------------------------------------ |
| `frontend/src/middleware.ts`                             | Deleted — renamed to `proxy.ts`                              |
| `frontend/src/proxy.ts`                                  | New — Next.js 16 proxy convention; explicit `export default` |
| `frontend/next.config.ts`                                | Exclude `/api/auth/**` from backend rewrite                  |
| `frontend/.env.local`                                    | `NEXTAUTH_SECRET` → `AUTH_SECRET`, added `AUTH_URL`          |
| `frontend/src/app/(auth)/login/page.tsx`                 | Full working login form                                      |
| `frontend/src/app/(dashboard)/portfolio/page.tsx`        | Remove stale `days={30}` prop                                |
| `frontend/Dockerfile`                                    | Remove build-time secret ARG/ENV injection                   |
| `frontend/src/components/layout/UserMenu.tsx`            | Real session data, working sign-out and nav                  |
| `frontend/src/components/portfolio/SummaryCards.tsx`     | Graceful zero state when backend down                        |
| `frontend/src/components/charts/PerformanceChart.tsx`    | Fallback to synthetic series; removed `days` prop            |
| `frontend/src/components/charts/AllocationChart.tsx`     | Empty state instead of error                                 |
| `frontend/src/components/portfolio/HoldingsTable.tsx`    | Empty state instead of error                                 |
| `portfolio-service/src/main/resources/application.yml`   | Default profile `local`                                      |
| `market-data-service/src/main/resources/application.yml` | Default profile `local`                                      |
| `api-gateway/src/main/resources/application.yml`         | Default profile `local`                                      |

---

## Commits

| Hash      | Message                                                                           |
| --------- | --------------------------------------------------------------------------------- |
| `13d16cc` | hotfix: fix auth, routing, profile activation and frontend error states           |
| `369bdbb` | fix: remove stale days prop from PerformanceChart and clean up Dockerfile secrets |
| `9680430` | fix: use explicit default export in proxy.ts for Next.js 16 build validation      |
