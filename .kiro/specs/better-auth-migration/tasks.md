# Implementation Plan: Better Auth Migration

## Overview

Migrate the Next.js frontend authentication from NextAuth (Auth.js) v5 to Better Auth in four strict phases. Phase 1 (The Purge) removes all NextAuth artifacts first — next-auth and better-auth must NOT coexist to avoid type collisions. Phase 2 installs Better Auth and creates the core server/client/route config. Phase 3 refactors all consuming components. Phase 4 aligns the database schema and E2E test helpers.

## Tasks

- [x] 1. Phase 1 — Purge NextAuth
  - [x] 1.1 Uninstall next-auth package
    - Run `npm uninstall next-auth` in the `frontend/` directory
    - Verify `next-auth` is removed from both `dependencies` and `devDependencies` in `package.json`
    - This MUST complete before any Better Auth package is installed to avoid type collisions in the IDE
    - _Requirements: 1.1_

  - [x] 1.2 Delete NextAuth configuration files
    - Delete `frontend/src/auth.ts` (NextAuth server config with SignJWT, jwtVerify, callbacks)
    - Delete `frontend/src/auth.config.ts` (NextAuth credentials provider config)
    - Delete `frontend/src/types/next-auth.d.ts` (NextAuth type augmentations)
    - Delete the `frontend/src/app/api/auth/[...nextauth]/route.ts` route handler file and its directory
    - _Requirements: 1.2, 1.3, 1.4, 1.5_

  - [x] 1.3 Remove NextAuth SessionProvider from root layout
    - Delete `frontend/src/components/layout/SessionProvider.tsx`
    - In `frontend/src/app/layout.tsx`: remove the `SessionProvider` import, remove the `auth()` call, remove the `<SessionProvider session={session}>` wrapper, and remove the `import { auth } from "@/auth"` line
    - Keep `ThemeProvider` and `QueryProvider` wrappers intact
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 1.4 Update environment variables in `.env.local`
    - Remove `AUTH_SECRET` variable
    - Remove `AUTH_URL` variable
    - Remove `AUTH_TRUST_HOST` variable
    - Retain `AUTH_JWT_SECRET` (shared with API Gateway)
    - Retain `NEXT_PUBLIC_API_BASE_URL` and `API_PROXY_TARGET`
    - Add `DATABASE_URL=postgresql://postgres:postgres@localhost:5432/wealthtracker`
    - Add `BETTER_AUTH_SECRET` set to the same value as `AUTH_JWT_SECRET` (e.g., `local-dev-secret-change-me-min-32-chars`)
    - Add `BETTER_AUTH_URL=http://localhost:3000`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 16.1_

- [x] 2. Phase 2 — Better Auth Core Setup
  - [x] 2.1 Install better-auth package and pg driver
    - Run `npm install better-auth pg` in the `frontend/` directory
    - Run `npm install -D @types/pg` for TypeScript types
    - Verify `better-auth` appears in `package.json` dependencies
    - _Requirements: 4.1_

  - [x] 2.2 Create Better Auth server configuration
    - Create `frontend/src/lib/auth.ts` exporting the `auth` instance from `betterAuth()`
    - Configure `database: new Pool({ connectionString: process.env.DATABASE_URL })` using `pg`
    - Enable `emailAndPassword: { enabled: true }`
    - Configure `session.cookieCache` with `strategy: "jwt"`, `maxAge: 300`, `enabled: true`
    - Set `session.expiresIn: 60 * 60` (1 hour, matching current JWT expiry)
    - Set `secret: process.env.BETTER_AUTH_SECRET`
    - Configure table name prefix `ba_` to avoid conflicts with Flyway-managed tables
    - The `BETTER_AUTH_SECRET` must equal `AUTH_JWT_SECRET` so the API Gateway can verify tokens with `NimbusReactiveJwtDecoder.withSecretKey()`
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 14.1, 14.2, 16.2_

  - [x] 2.3 Create Better Auth client configuration
    - Create `frontend/src/lib/auth-client.ts`
    - Export `useSession`, `signIn`, `signOut`, and `getSession` from `createAuthClient()`
    - The client auto-detects base URL from browser origin
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 2.4 Create Better Auth API route handler
    - Create `frontend/src/app/api/auth/[...all]/route.ts`
    - Export `GET` and `POST` handlers using `toNextJsHandler(auth)` from `better-auth/next-js`
    - Import `auth` from `@/lib/auth`
    - _Requirements: 6.1, 6.2, 6.3_

- [x] 3. Checkpoint — Verify Phase 1 & 2 complete
  - Ensure no `next-auth` references remain in `package.json` or source files
  - Ensure `better-auth` is installed and the server/client/route files compile without errors
  - Run `npm run lint` to check for import errors
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Phase 3 — Component Refactoring
  - [x] 4.1 Refactor `useAuthenticatedUserId` hook
    - In `frontend/src/lib/hooks/useAuthenticatedUserId.ts`: replace `import { useSession } from "next-auth/react"` with `import { useSession } from "@/lib/auth-client"`
    - Remove the `readTokenFromCookie()` helper function entirely (Better Auth's JWT cookie cache eliminates the hydration workaround)
    - Rewrite the hook body to use Better Auth's session shape: `session.user.id` for userId, `session.session.token` for the JWT token
    - Use `isPending` instead of `status === "loading"` for the loading state check
    - Preserve the `AuthenticatedUser` return type interface unchanged so all consuming TanStack Query hooks remain unmodified
    - CRITICAL: The `enabled: status === "authenticated" && !!token` guard on all TanStack Query hooks in `usePortfolio.ts` depends on this hook returning the correct status. Verify the status mapping: `isPending` → `"loading"`, `!isPending && !!session?.user` → `"authenticated"`, `!isPending && !session?.user` → `"unauthenticated"`
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

  - [x] 4.2 Refactor `PortfolioPageContent` session gate
    - In `frontend/src/components/portfolio/PortfolioPageContent.tsx`: replace `import { useSession } from "next-auth/react"` with `import { useSession } from "@/lib/auth-client"`
    - Change `const { status } = useSession()` to `const { data: session, isPending } = useSession()`
    - Map: `isPending` → render skeleton, `!isPending && !session?.user` → redirect to `/login`, `!isPending && session?.user` → render portfolio components
    - CRITICAL: Retain the `enabled: !isPending && !!session` (or equivalent Better Auth state) guard pattern — TanStack Query hooks must NOT fire requests without a valid token
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 4.3 Refactor Login page
    - In `frontend/src/app/(auth)/login/page.tsx`: replace `import { signIn } from "next-auth/react"` with `import { signIn } from "@/lib/auth-client"`
    - Change form submission from `signIn("credentials", { username, password, redirect: false })` to `signIn.email({ email, password })`
    - Rename the `username` form field to `email` and update the label, placeholder, and autocomplete attribute
    - Update hint text from `user-001 / password` to `dev@local / password`
    - Handle the Better Auth error response shape (check for `error` property on the result)
    - On success, call `router.push("/overview")`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [x] 4.4 Refactor UserMenu component
    - In `frontend/src/components/layout/UserMenu.tsx`: replace `import { useSession, signOut } from "next-auth/react"` with `import { useSession, signOut } from "@/lib/auth-client"`
    - Change `const { data: session, status } = useSession()` to `const { data: session, isPending } = useSession()`
    - Update loading check from `status === "loading"` to `isPending`
    - Read user name/email from `session?.user?.name` and `session?.user?.email` (same path in Better Auth)
    - Change sign-out from `signOut({ callbackUrl: "/login" })` to `signOut()` followed by `router.push("/login")`
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 4.5 Refactor server-side `fetchWithAuth`
    - In `frontend/src/lib/api/fetchWithAuth.ts`: replace `import { auth } from "@/auth"` with `import { auth } from "@/lib/auth"` and add `import { headers } from "next/headers"`
    - Change `const session = await auth()` to `const session = await auth.api.getSession({ headers: await headers() })`
    - Change token extraction from `session?.accessToken` to `session?.session?.token`
    - Keep `fetchWithAuthClient` unchanged — it already accepts a raw JWT string parameter
    - Maintain the same function signature and return type
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

- [x] 5. Checkpoint — Verify component refactoring compiles
  - Run `npm run lint` to verify no broken imports
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Phase 3 (continued) — Update Unit Tests
  - [x] 6.1 Update `useAuthenticatedUserId` tests
    - In `frontend/src/lib/hooks/useAuthenticatedUserId.test.ts`: change mock from `vi.mock("next-auth/react")` to `vi.mock("@/lib/auth-client")`
    - Update mock return values to match Better Auth's `useSession` shape: `{ data: { user: {...}, session: { token: "..." } }, isPending: false }` instead of `{ data: { user: {...}, accessToken: "..." }, status: "authenticated" }`
    - Update all test cases to use the new session shape
    - Remove the `@/auth` mock if present
    - _Requirements: 15.1, 15.3_

  - [x] 6.2 Update `usePortfolio` hook tests
    - In `frontend/src/lib/hooks/usePortfolio.test.ts`: change mock from `vi.mock("next-auth/react")` to `vi.mock("@/lib/auth-client")`
    - Replace `vi.mock("@/auth")` with `vi.mock("@/lib/auth")`
    - Update `AUTHENTICATED_SESSION` and other mock return values to Better Auth's `useSession` shape
    - Verify the `enabled` guard tests still confirm queries don't fire when `isPending` is true or session is absent
    - _Requirements: 15.1, 15.2, 15.4_

  - [x] 6.3 Update `fetchWithAuth` tests
    - In `frontend/src/lib/api/fetchWithAuth.test.ts`: change mock from `vi.mock("@/auth")` to `vi.mock("@/lib/auth")`
    - Update mock to return Better Auth's server-side session shape: `{ session: { token: "..." }, user: {...} }`
    - Add a test for the server-side `fetchWithAuth` function that verifies `auth.api.getSession` is called and the Bearer token is attached
    - Existing `fetchWithAuthClient` tests should remain unchanged
    - _Requirements: 15.2, 15.5_

  - [ ]\* 6.4 Write property test: JWT round-trip preserves user identity
    - **Property 1: JWT round-trip preserves user identity**
    - Install `fast-check` as a dev dependency if not already present
    - Create a property test that generates random UUID strings with `fc.uuid()`
    - For each UUID, sign a JWT with HS256 using the test secret, then verify it and assert `sub` equals the original UUID
    - Minimum 100 iterations
    - **Validates: Requirements 4.5, 4.6, 4.8, 14.1, 14.3**

  - [ ]\* 6.5 Write property test: Auth hook faithfully extracts session data
    - **Property 2: Auth hook faithfully extracts session data**
    - Generate random `{ userId: string, token: string }` pairs using `fc.string()` and `fc.string()`
    - Mock `useSession` from `@/lib/auth-client` to return a Better Auth session with those values
    - Call `useAuthenticatedUserId()` via `renderHook`
    - Assert returned `userId` and `token` match the generated input
    - Minimum 100 iterations
    - **Validates: Requirements 10.2, 10.3**

  - [ ]\* 6.6 Write property test: fetchWithAuth attaches Bearer token from session
    - **Property 3: fetchWithAuth attaches Bearer token from session**
    - Generate random JWT token strings using `fc.string({ minLength: 1 })`
    - Mock `auth.api.getSession` from `@/lib/auth` to return a session with that token
    - Call `fetchWithAuth` with a mock `fetch`
    - Assert the `Authorization` header equals `Bearer <token>`
    - Minimum 100 iterations
    - **Validates: Requirements 11.2**

- [x] 7. Checkpoint — Verify all unit tests pass
  - Run `npm run test` in the `frontend/` directory
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Phase 4 — Database Schema & Dev User Seed
  - [x] 8.1 Generate Better Auth database tables
    - Run `npx @better-auth/cli@latest migrate` in the `frontend/` directory, or create a manual SQL migration script
    - This creates the `ba_user`, `ba_session`, `ba_account`, and `ba_verification` tables with the `ba_` prefix
    - CRITICAL: The table generation MUST NOT drop or alter the existing `users`, `portfolios`, `asset_holdings`, or `market_prices` tables created by Flyway migrations. The `ba_` table prefix is the safety mechanism that prevents this — verify the prefix is correctly configured in `lib/auth.ts` before running
    - The migration is idempotent (`CREATE TABLE IF NOT EXISTS` semantics)
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 8.2 Create dev user seed script
    - Create a SQL seed script (e.g., `frontend/scripts/seed-dev-user.sql`) that inserts the dev user into `ba_user` and `ba_account`
    - User ID: `00000000-0000-0000-0000-000000000001` (or the ID matching the Flyway V4 seed)
    - Email: `dev@local`, Name: `Dev User`
    - Password: `password` (hashed with scrypt, Better Auth's default hashing algorithm)
    - Use `ON CONFLICT DO NOTHING` for idempotency
    - Alternatively, create a TypeScript seed script that uses Better Auth's `auth.api.signUpEmail` to create the user programmatically
    - _Requirements: 12.4_

- [x] 9. Phase 4 (continued) — E2E Auth Helper
  - [x] 9.1 Rewrite E2E auth helper for Better Auth
    - In `frontend/tests/e2e/helpers/auth.ts`: replace the `injectAuthSession` function with a standard UI login flow
    - The new flow: navigate to `/login`, fill the email field with `dev@local`, fill the password field with `password`, click submit, wait for redirect to `/overview`, verify the page heading is visible within 10 seconds
    - Favor the standard UI login flow (typing credentials into the /login form and clicking submit) rather than complex cookie injection or CSRF POST strategies. Better Auth handles session state cleanly without the hydration bugs that plagued NextAuth
    - Remove all NextAuth-specific CSRF token fetching logic
    - MUST retain the `mintJwt` utility function — it is used for direct API Gateway Bearer token generation in tests that bypass the UI (e.g., `api.ts` helper)
    - Update the `mintJwt` default userId if needed to match the new dev user ID
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

  - [ ]\* 9.2 Write E2E smoke test for auth flow
    - Verify login with `dev@local` / `password` succeeds and redirects to `/overview`
    - Verify session persists across page navigations
    - Verify sign-out redirects to `/login`
    - _Requirements: 13.3, 13.4_

- [x] 10. Final checkpoint — Full verification
  - Run `npm run lint` to verify no broken imports or lint errors
  - Run `npm run test` to verify all unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Phase 1 MUST complete entirely before Phase 2 begins — next-auth and better-auth must never coexist in package.json to avoid IDE type collisions
- The `ba_` table prefix is the critical safety mechanism preventing Better Auth from touching Flyway-managed tables
- All TanStack Query hooks retain their `enabled: !isPending && !!session` guards to prevent firing requests without a valid token
- The `mintJwt` utility in E2E helpers is retained for direct API Gateway Bearer token generation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- Each task references specific requirements for traceability
