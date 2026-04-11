# Requirements Document

## Introduction

Migrate the Next.js frontend authentication from NextAuth (Auth.js) v5 to Better Auth. The current NextAuth implementation suffers from client-side hydration issues in the standalone build that block the E2E test pipeline. Better Auth provides a clean, type-safe, standard-Node authentication solution that connects directly to PostgreSQL and produces HS256 JWTs compatible with the existing Spring Boot API Gateway.

The migration is executed in four phases: (1) remove all NextAuth artifacts, (2) install and configure Better Auth core, (3) refactor all consuming components, and (4) align the database schema and E2E test helpers.

## Glossary

- **Better_Auth**: A TypeScript-native authentication library for Node.js that provides server and client modules, direct database connectivity, and plugin-based credential authentication.
- **Auth_Server**: The Better Auth server-side configuration module exported from `lib/auth.ts`, responsible for database connection, JWT signing, and plugin registration.
- **Auth_Client**: The Better Auth client-side configuration module exported from `lib/auth-client.ts`, providing `useSession`, `signIn`, and `signOut` hooks for React components.
- **API_Route_Handler**: The Next.js catch-all API route at `app/api/auth/[...all]/route.ts` that delegates HTTP requests to the Auth_Server.
- **API_Gateway**: The Spring Boot Cloud Gateway service that validates incoming HS256-signed JWTs using `NimbusReactiveJwtDecoder.withSecretKey(AUTH_JWT_SECRET)`.
- **Session_Gate**: A client component pattern that conditionally renders child components based on the authenticated session status (e.g., `PortfolioPageContent`).
- **Auth_Hook**: A custom React hook (`useAuthenticatedUserId`) that extracts the user ID and raw JWT token from the session for use in TanStack Query hooks.
- **E2E_Auth_Helper**: The Playwright helper module at `frontend/tests/e2e/helpers/auth.ts` that programmatically authenticates test sessions.
- **PostgreSQL_Database**: The local PostgreSQL 16 instance used by both the portfolio-service (via Flyway) and Better Auth for user/session storage.
- **JWT_Secret**: The shared HS256 signing key (`AUTH_JWT_SECRET`) used by both the frontend auth system and the API_Gateway to sign and verify JWTs.

## Requirements

### Requirement 1: Remove NextAuth Package and Dependencies

**User Story:** As a developer, I want all NextAuth artifacts completely removed from the frontend project, so that there are no residual dependencies or dead code from the old auth system.

#### Acceptance Criteria

1. WHEN the migration begins, THE Build_System SHALL have the `next-auth` package uninstalled from `package.json` dependencies.
2. WHEN the migration begins, THE Build_System SHALL remove the `frontend/src/auth.ts` file containing the NextAuth server configuration.
3. WHEN the migration begins, THE Build_System SHALL remove the `frontend/src/auth.config.ts` file containing the NextAuth credentials provider.
4. WHEN the migration begins, THE Build_System SHALL remove the `frontend/src/types/next-auth.d.ts` type declaration file.
5. WHEN the migration begins, THE Build_System SHALL delete the `frontend/src/app/api/auth/[...nextauth]/` directory and its route handler.

### Requirement 2: Remove NextAuth Environment Variables

**User Story:** As a developer, I want NextAuth-specific environment variables removed from configuration files, so that the environment is clean and only contains variables relevant to Better Auth.

#### Acceptance Criteria

1. WHEN the migration begins, THE Configuration SHALL remove the `AUTH_SECRET` variable from `.env.local`.
2. WHEN the migration begins, THE Configuration SHALL remove the `AUTH_URL` variable from `.env.local`.
3. WHEN the migration begins, THE Configuration SHALL remove the `AUTH_TRUST_HOST` variable from `.env.local`.
4. WHEN the migration begins, THE Configuration SHALL retain the `AUTH_JWT_SECRET` variable in `.env.local` because the API_Gateway depends on the same shared secret.
5. WHEN the migration begins, THE Configuration SHALL retain the `NEXT_PUBLIC_API_BASE_URL` and `API_PROXY_TARGET` variables in `.env.local`.

### Requirement 3: Remove NextAuth SessionProvider from Root Layout

**User Story:** As a developer, I want the NextAuth SessionProvider wrapper removed from the root layout, so that the component tree no longer depends on NextAuth's React context.

#### Acceptance Criteria

1. WHEN the migration begins, THE Root_Layout SHALL remove the `SessionProvider` import from `@/components/layout/SessionProvider`.
2. WHEN the migration begins, THE Root_Layout SHALL remove the server-side `auth()` call that fetches the NextAuth session.
3. WHEN the migration begins, THE Root_Layout SHALL remove the `<SessionProvider session={session}>` wrapper from the component tree.
4. WHEN the migration begins, THE Build_System SHALL delete the `frontend/src/components/layout/SessionProvider.tsx` file.

### Requirement 4: Install and Configure Better Auth Server

**User Story:** As a developer, I want a Better Auth server configuration that connects to PostgreSQL and signs JWTs with HS256, so that the authentication backend is fully operational and compatible with the API_Gateway.

#### Acceptance Criteria

1. THE Build_System SHALL have the `better-auth` package installed as a dependency in `package.json`.
2. THE Auth_Server SHALL be exported from `frontend/src/lib/auth.ts`.
3. THE Auth_Server SHALL connect to the PostgreSQL_Database using the `DATABASE_URL` environment variable.
4. THE Auth_Server SHALL use the `emailAndPassword` plugin for credential-based authentication.
5. THE Auth_Server SHALL sign session JWTs using the HS256 algorithm with the JWT_Secret (`AUTH_JWT_SECRET` environment variable).
6. WHEN the Auth_Server signs a JWT, THE Auth_Server SHALL include the `sub` claim set to the authenticated user's ID.
7. WHEN the Auth_Server signs a JWT, THE Auth_Server SHALL set the JWT expiration to 1 hour from the time of issuance.
8. THE Auth_Server SHALL produce JWTs that the API_Gateway can verify using `NimbusReactiveJwtDecoder.withSecretKey()` with the same JWT_Secret.

### Requirement 5: Configure Better Auth Client

**User Story:** As a developer, I want a Better Auth client module that exports typed React hooks, so that client components can access session state, sign in, and sign out.

#### Acceptance Criteria

1. THE Auth_Client SHALL be exported from `frontend/src/lib/auth-client.ts`.
2. THE Auth_Client SHALL export a `useSession` hook that returns the current session data and loading state.
3. THE Auth_Client SHALL export a `signIn` function for programmatic credential-based authentication.
4. THE Auth_Client SHALL export a `signOut` function for programmatic session termination.
5. THE Auth_Client SHALL derive its base URL from the application's origin so that auth API calls route to the correct endpoint.

### Requirement 6: Create Better Auth API Route Handler

**User Story:** As a developer, I want a Next.js catch-all API route that delegates auth requests to Better Auth, so that sign-in, sign-out, and session endpoints are served by the application.

#### Acceptance Criteria

1. THE API_Route_Handler SHALL be located at `frontend/src/app/api/auth/[...all]/route.ts`.
2. THE API_Route_Handler SHALL export GET and POST handlers that delegate to the Auth_Server's request handler.
3. WHEN the API_Route_Handler receives a request to `/api/auth/*`, THE API_Route_Handler SHALL forward the request to the Auth_Server for processing.

### Requirement 7: Refactor PortfolioPageContent Session Gate

**User Story:** As a developer, I want the PortfolioPageContent component to use Better Auth's session hook, so that the session gate works correctly without NextAuth.

#### Acceptance Criteria

1. THE Session_Gate in `PortfolioPageContent` SHALL import `useSession` from `@/lib/auth-client` instead of `next-auth/react`.
2. WHEN the session is loading (`isPending` is true), THE Session_Gate SHALL render the skeleton loading state.
3. WHEN the session data is absent and loading is complete, THE Session_Gate SHALL redirect the user to `/login`.
4. WHEN the session data is present, THE Session_Gate SHALL render the portfolio data components (SummaryCards, PerformanceChart, AllocationChart, HoldingsTable).

### Requirement 8: Refactor Login Page

**User Story:** As a developer, I want the login page to authenticate using Better Auth's signIn function, so that users can log in with email and password credentials.

#### Acceptance Criteria

1. THE Login_Page SHALL import `signIn` from `@/lib/auth-client` instead of `next-auth/react`.
2. WHEN the user submits the login form, THE Login_Page SHALL call the Auth_Client `signIn` function with `email` and `password` fields.
3. WHEN authentication succeeds, THE Login_Page SHALL navigate the user to `/overview`.
4. WHEN authentication fails, THE Login_Page SHALL display the error message "Invalid username or password." to the user.
5. WHILE the sign-in request is in progress, THE Login_Page SHALL disable the submit button and display "Signing in…" as the button text.

### Requirement 9: Refactor UserMenu Component

**User Story:** As a developer, I want the UserMenu component to use Better Auth hooks, so that it displays the current user's information and supports sign-out.

#### Acceptance Criteria

1. THE UserMenu SHALL import `useSession` and `signOut` from `@/lib/auth-client` instead of `next-auth/react`.
2. THE UserMenu SHALL read the user's name and email from the Better Auth session data structure.
3. WHEN the user clicks "Sign out", THE UserMenu SHALL call the Auth_Client `signOut` function and redirect to `/login`.
4. WHILE the session is loading, THE UserMenu SHALL render a pulsing placeholder avatar.

### Requirement 10: Refactor useAuthenticatedUserId Hook

**User Story:** As a developer, I want the useAuthenticatedUserId hook to use Better Auth's session, so that TanStack Query hooks receive the correct user ID and JWT token.

#### Acceptance Criteria

1. THE Auth_Hook SHALL import `useSession` from `@/lib/auth-client` instead of `next-auth/react`.
2. WHEN the session is authenticated, THE Auth_Hook SHALL return the user ID from the Better Auth session's `user.id` field.
3. WHEN the session is authenticated, THE Auth_Hook SHALL return the raw JWT token from the Better Auth session for use as a Bearer token.
4. WHEN the session is loading (`isPending` is true), THE Auth_Hook SHALL return status `"loading"` with empty userId and token.
5. WHEN the session is absent, THE Auth_Hook SHALL return status `"unauthenticated"` with empty userId and token.
6. THE Auth_Hook SHALL maintain the same `AuthenticatedUser` return type interface so that all consuming TanStack Query hooks remain unchanged.

### Requirement 11: Refactor Server-Side fetchWithAuth

**User Story:** As a developer, I want the server-side fetchWithAuth utility to retrieve the session from Better Auth, so that Server Components and Route Handlers can make authenticated API calls.

#### Acceptance Criteria

1. THE fetchWithAuth function SHALL retrieve the current session using Better Auth's server-side session API instead of NextAuth's `auth()` function.
2. WHEN a valid session exists, THE fetchWithAuth function SHALL attach the JWT token as an `Authorization: Bearer` header.
3. WHEN no session exists, THE fetchWithAuth function SHALL omit the Authorization header.
4. THE fetchWithAuth function SHALL maintain the same function signature and return type so that all consuming code remains unchanged.

### Requirement 12: Database Schema for Better Auth

**User Story:** As a developer, I want Better Auth's required database tables created in the PostgreSQL instance, so that user accounts and sessions can be persisted.

#### Acceptance Criteria

1. THE PostgreSQL_Database SHALL contain the tables required by Better Auth (user, session, account, verification tables).
2. THE Better Auth schema SHALL use a table name prefix or schema separation strategy that avoids conflicts with the portfolio-service Flyway-managed tables.
3. WHEN Better Auth generates or migrates its schema, THE PostgreSQL_Database SHALL retain all existing portfolio-service tables and data intact.
4. THE PostgreSQL_Database SHALL contain a seeded user record matching the local dev credentials (user ID `user-001`, email `dev@local`, password `password`) so that local development and E2E tests function without manual setup.

### Requirement 13: Update E2E Authentication Helper

**User Story:** As a developer, I want the Playwright E2E auth helper to authenticate using Better Auth's API, so that E2E tests can programmatically log in without hydration issues.

#### Acceptance Criteria

1. THE E2E_Auth_Helper SHALL authenticate against Better Auth's sign-in API endpoint instead of NextAuth's CSRF-based credentials endpoint.
2. WHEN the E2E_Auth_Helper authenticates, THE E2E_Auth_Helper SHALL use the seeded credentials (email `dev@local`, password `password`).
3. WHEN authentication succeeds, THE E2E_Auth_Helper SHALL ensure the browser session cookies are set so that subsequent page navigations are authenticated.
4. THE E2E_Auth_Helper SHALL navigate to `/overview` and verify the page heading is visible within 10 seconds as confirmation of successful authentication.
5. THE E2E_Auth_Helper SHALL retain the `mintJwt` utility function for direct API Gateway Bearer token generation in tests that bypass the UI.

### Requirement 14: JWT Compatibility with API Gateway

**User Story:** As a developer, I want Better Auth's JWTs to be accepted by the Spring Boot API Gateway, so that the existing backend validation pipeline continues to work without changes.

#### Acceptance Criteria

1. THE Auth_Server SHALL sign JWTs using the HS256 algorithm exclusively (matching the API_Gateway's `MacAlgorithm.HS256` configuration).
2. THE Auth_Server SHALL use the same `AUTH_JWT_SECRET` environment variable value that the API_Gateway reads from `auth.jwt.secret`.
3. WHEN the Auth_Server issues a JWT, THE JWT SHALL contain a `sub` claim with the user's ID so that the API_Gateway can extract the authenticated principal.
4. WHEN the API_Gateway receives a JWT issued by the Auth_Server, THE API_Gateway SHALL successfully decode and validate the JWT without any configuration changes.

### Requirement 15: Update Unit Tests

**User Story:** As a developer, I want all unit tests updated to mock Better Auth instead of NextAuth, so that the test suite passes after the migration.

#### Acceptance Criteria

1. WHEN unit tests mock the session hook, THE Test_Suite SHALL mock `@/lib/auth-client` instead of `next-auth/react`.
2. WHEN unit tests mock the server-side auth function, THE Test_Suite SHALL mock `@/lib/auth` instead of `@/auth`.
3. THE Test_Suite SHALL pass all existing test cases for `useAuthenticatedUserId` with the updated mocks.
4. THE Test_Suite SHALL pass all existing test cases for `usePortfolio` hooks with the updated mocks.
5. THE Test_Suite SHALL pass all existing test cases for `fetchWithAuth` with the updated mocks.

### Requirement 16: Add DATABASE_URL Environment Variable

**User Story:** As a developer, I want a `DATABASE_URL` environment variable configured for the frontend, so that Better Auth can connect directly to the PostgreSQL database.

#### Acceptance Criteria

1. THE Configuration SHALL add a `DATABASE_URL` environment variable to `.env.local` pointing to the local PostgreSQL instance (e.g., `postgresql://postgres:postgres@localhost:5432/wealthtracker`).
2. THE Auth_Server SHALL read the `DATABASE_URL` environment variable to establish the database connection.
3. THE `DATABASE_URL` SHALL use the same PostgreSQL instance and database that the portfolio-service connects to via Flyway.
