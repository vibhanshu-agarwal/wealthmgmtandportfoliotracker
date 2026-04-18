# Design Document: Better Auth Migration

## Overview

This design covers the complete migration from NextAuth (Auth.js) v5 to Better Auth in the Next.js 16 frontend. The migration is motivated by client-side hydration issues in the standalone build that block the E2E test pipeline. Better Auth provides a standard-Node, TypeScript-native authentication solution with direct PostgreSQL connectivity and HS256 JWT signing compatible with the existing Spring Boot API Gateway.

The migration is executed in four phases:

1. **Phase 1 — Purge NextAuth**: Remove all NextAuth packages, config files, route handlers, SessionProvider, and environment variables.
2. **Phase 2 — Better Auth Core Setup**: Install Better Auth, create server config (`lib/auth.ts`), client config (`lib/auth-client.ts`), and the catch-all API route handler.
3. **Phase 3 — Component Refactoring**: Update PortfolioPageContent, Login page, UserMenu, `useAuthenticatedUserId` hook, and `fetchWithAuth` utility to use Better Auth APIs.
4. **Phase 4 — Database & E2E**: Create Better Auth database tables (with prefix to avoid Flyway conflicts), seed the dev user, and update the Playwright E2E auth helper.

### Key Design Decisions

- **Cookie cache strategy: `"jwt"` with HS256** — Better Auth's session cookie cache is configured with `strategy: "jwt"`, producing HS256-signed JWTs that the Spring Boot API Gateway can verify directly using `NimbusReactiveJwtDecoder.withSecretKey()`. No changes to the API Gateway are required.
- **Direct pg.Pool database adapter** — Better Auth connects to the same PostgreSQL instance as portfolio-service using `pg.Pool` with the `DATABASE_URL` environment variable. No ORM layer is needed.
- **Table prefix `ba_`** — Better Auth tables use the `ba_` prefix (e.g., `ba_user`, `ba_session`, `ba_account`, `ba_verification`) to avoid conflicts with Flyway-managed tables (`users`, `portfolios`, `asset_holdings`, `market_prices`).
- **emailAndPassword plugin** — Credential-based login using email and password, replacing the NextAuth Credentials provider.
- **Simplified useAuthenticatedUserId** — The two-tier cookie-reading approach is replaced with a direct `useSession()` call from Better Auth's React client, since Better Auth does not suffer from the same hydration issues that necessitated the cookie fallback.
- **E2E: Standard UI login flow** — The Playwright helper authenticates via the real login form rather than cookie injection, matching the production flow.

## Architecture

```mermaid
graph TB
    subgraph "Next.js Frontend"
        A[Login Page] -->|signIn.email| B[Auth Client<br/>lib/auth-client.ts]
        C[PortfolioPageContent] -->|useSession| B
        D[UserMenu] -->|useSession, signOut| B
        E[useAuthenticatedUserId] -->|useSession| B
        F[fetchWithAuth] -->|auth.api.getSession| G[Auth Server<br/>lib/auth.ts]

        B -->|HTTP| H[Route Handler<br/>app/api/auth/.../route.ts]
        H --> G
    end

    subgraph "Database"
        G -->|pg.Pool| I[(PostgreSQL)]
        I --- J[ba_user<br/>ba_session<br/>ba_account<br/>ba_verification]
        I --- K[users<br/>portfolios<br/>asset_holdings<br/>market_prices]
    end

    subgraph "API Gateway (Spring Boot)"
        L[JwtDecoderConfig] -->|HS256 verify| M[NimbusReactiveJwtDecoder]
    end

    E -->|Bearer token| L

    style J fill:#e1f5fe
    style K fill:#fff3e0
```

### Request Flow

1. **Client-side auth**: React components call `useSession()` from `@/lib/auth-client` to get session state. The session is cached in a JWT cookie (`strategy: "jwt"`).
2. **Sign-in**: Login page calls `signIn.email({ email, password })` → HTTP POST to `/api/auth/sign-in/email` → Auth Server validates credentials against `ba_user` table → returns session cookie with HS256-signed JWT.
3. **API calls**: `useAuthenticatedUserId` extracts the JWT token from the session. TanStack Query hooks attach it as `Authorization: Bearer <token>` via `fetchWithAuthClient`.
4. **Server-side auth**: `fetchWithAuth` calls `auth.api.getSession({ headers })` to retrieve the session server-side, then attaches the JWT as a Bearer token.
5. **API Gateway validation**: Spring Boot's `NimbusReactiveJwtDecoder.withSecretKey(AUTH_JWT_SECRET)` verifies the HS256 JWT. The `sub` claim provides the authenticated user ID.

## Components and Interfaces

### Phase 1: Files to Remove

| File                                                 | Purpose                                                |
| ---------------------------------------------------- | ------------------------------------------------------ |
| `frontend/src/auth.ts`                               | NextAuth server config (SignJWT, jwtVerify, callbacks) |
| `frontend/src/auth.config.ts`                        | NextAuth credentials provider config                   |
| `frontend/src/types/next-auth.d.ts`                  | NextAuth type augmentations                            |
| `frontend/src/app/api/auth/[...nextauth]/route.ts`   | NextAuth route handler                                 |
| `frontend/src/components/layout/SessionProvider.tsx` | NextAuth SessionProvider wrapper                       |

### Phase 2: New Files

#### `frontend/src/lib/auth.ts` — Auth Server

```typescript
import { betterAuth } from "better-auth";
import { Pool } from "pg";

export const auth = betterAuth({
  database: new Pool({
    connectionString: process.env.DATABASE_URL,
  }),
  emailAndPassword: {
    enabled: true,
  },
  session: {
    cookieCache: {
      enabled: true,
      maxAge: 300, // 5 minutes
      strategy: "jwt",
    },
    expiresIn: 60 * 60, // 1 hour (matches current JWT expiry)
  },
  secret: process.env.BETTER_AUTH_SECRET,
  advanced: {
    generateId: false, // let PostgreSQL generate IDs
  },
  // Table name mapping to use ba_ prefix
  // Avoids conflicts with Flyway-managed tables
});
```

The `sub` claim in the JWT is automatically set to the user's ID by Better Auth when using the `"jwt"` cookie cache strategy. The JWT is signed with `BETTER_AUTH_SECRET` using HS256.

**Critical**: `BETTER_AUTH_SECRET` must be set to the same value as `AUTH_JWT_SECRET` so the API Gateway can verify the tokens.

#### `frontend/src/lib/auth-client.ts` — Auth Client

```typescript
import { createAuthClient } from "better-auth/react";

export const { useSession, signIn, signOut, getSession } = createAuthClient();
```

The client auto-detects the base URL from the browser origin, routing auth API calls to `/api/auth/*`.

#### `frontend/src/app/api/auth/[...all]/route.ts` — Route Handler

```typescript
import { auth } from "@/lib/auth";
import { toNextJsHandler } from "better-auth/next-js";

export const { GET, POST } = toNextJsHandler(auth);
```

### Phase 3: Refactored Components

#### `useAuthenticatedUserId` Hook

The hook is simplified to use Better Auth's `useSession()` directly. The two-tier cookie-reading fallback is removed because Better Auth's JWT cookie cache provides the session data synchronously without the hydration issues that plagued NextAuth.

```typescript
"use client";

import { useSession } from "@/lib/auth-client";

export interface AuthenticatedUser {
  userId: string;
  token: string;
  status: "authenticated" | "loading" | "unauthenticated";
}

export function useAuthenticatedUserId(): AuthenticatedUser {
  const { data: session, isPending } = useSession();

  if (isPending) {
    return { userId: "", token: "", status: "loading" };
  }

  if (session?.user?.id && session?.session?.token) {
    return {
      userId: session.user.id,
      token: session.session.token,
      status: "authenticated",
    };
  }

  return { userId: "", token: "", status: "unauthenticated" };
}
```

The `AuthenticatedUser` interface is preserved so all consuming TanStack Query hooks (`usePortfolio`, `usePortfolioSummary`, etc.) remain unchanged.

#### `fetchWithAuth` Server-Side Utility

```typescript
import { auth } from "@/lib/auth";
import { headers } from "next/headers";

export async function fetchWithAuth<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const session = await auth.api.getSession({
    headers: await headers(),
  });
  const token = session?.session?.token;

  const reqHeaders: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };

  const response = await fetch(path, {
    method: "GET",
    ...init,
    headers: reqHeaders,
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${path}`);
  }

  return (await response.json()) as T;
}
```

The `fetchWithAuthClient` function remains unchanged — it already accepts a raw JWT string parameter.

#### Login Page

The login page switches from `signIn("credentials", ...)` to `signIn.email({ email, password })`. The form field names change from `username` to `email` to match Better Auth's emailAndPassword plugin. The hint text updates to show `dev@local` instead of `user-001`.

#### PortfolioPageContent

Replaces `useSession()` from `next-auth/react` with `useSession()` from `@/lib/auth-client`. The status mapping changes:

- `isPending` → loading skeleton
- `!session?.user` after loading → redirect to `/login`
- `session?.user` present → render portfolio components

#### UserMenu

Replaces `useSession` and `signOut` imports from `next-auth/react` with imports from `@/lib/auth-client`. Session data access changes from `session?.user?.name` to `session?.user?.name` (same path in Better Auth). Sign-out changes from `signOut({ callbackUrl: "/login" })` to `signOut()` followed by `router.push("/login")`.

### Phase 4: Database & E2E

#### Database Schema

Better Auth tables use the `ba_` prefix to avoid conflicts with Flyway-managed tables:

| Better Auth Table | Prefix Table Name | Purpose                                                                     |
| ----------------- | ----------------- | --------------------------------------------------------------------------- |
| `user`            | `ba_user`         | User accounts (id, name, email, emailVerified, image, createdAt, updatedAt) |
| `session`         | `ba_session`      | Active sessions (id, expiresAt, token, ipAddress, userAgent, userId)        |
| `account`         | `ba_account`      | Auth provider accounts (id, accountId, providerId, userId, password, etc.)  |
| `verification`    | `ba_verification` | Email verification tokens                                                   |

These tables are created by running `npx @better-auth/cli@latest migrate` with the table name prefix configured in the auth server config.

#### Dev User Seed

A SQL seed script inserts the dev user into Better Auth's `ba_user` and `ba_account` tables:

- User ID: `00000000-0000-0000-0000-000000000001` (matches Flyway V4 seed)
- Email: `dev@local`
- Password: `password` (hashed with scrypt, Better Auth's default)

This ensures local development and E2E tests work without manual setup.

#### E2E Auth Helper

The Playwright helper is simplified to use the standard UI login flow:

1. Navigate to `/login`
2. Fill email field with `dev@local`, password field with `password`
3. Click submit
4. Wait for redirect to `/overview`
5. Verify the page heading is visible within 10 seconds

The `mintJwt` utility is retained for direct API Gateway Bearer token generation in tests that bypass the UI.

## Data Models

### Better Auth Session Object (Client-Side)

```typescript
interface BetterAuthSession {
  user: {
    id: string; // UUID, maps to ba_user.id
    name: string;
    email: string;
    emailVerified: boolean;
    image: string | null;
    createdAt: Date;
    updatedAt: Date;
  };
  session: {
    id: string;
    token: string; // The HS256-signed JWT — used as Bearer token
    expiresAt: Date;
    userId: string;
    ipAddress: string | null;
    userAgent: string | null;
  };
}
```

### Better Auth Session Object (Server-Side)

The server-side `auth.api.getSession()` returns the same structure. The `session.token` field contains the raw JWT string that can be attached as a Bearer token.

### JWT Claims (HS256)

```json
{
  "sub": "00000000-0000-0000-0000-000000000001",
  "iat": 1719000000,
  "exp": 1719003600
}
```

The `sub` claim is the user's ID from `ba_user.id`. The API Gateway extracts this as the authenticated principal via `NimbusReactiveJwtDecoder`.

### Environment Variables

| Variable                   | Value (local dev)                                             | Purpose                                |
| -------------------------- | ------------------------------------------------------------- | -------------------------------------- |
| `DATABASE_URL`             | `postgresql://postgres:postgres@localhost:5432/wealthtracker` | Better Auth PostgreSQL connection      |
| `BETTER_AUTH_SECRET`       | Same as `AUTH_JWT_SECRET`                                     | JWT signing + session encryption       |
| `BETTER_AUTH_URL`          | `http://localhost:3000`                                       | Better Auth base URL                   |
| `AUTH_JWT_SECRET`          | `local-dev-secret-change-me-min-32-chars`                     | Retained for API Gateway compatibility |
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080`                                       | Retained — API proxy target            |
| `API_PROXY_TARGET`         | `http://localhost:8080`                                       | Retained — Next.js rewrite target      |

### Mapping: NextAuth → Better Auth

| NextAuth Concept                                | Better Auth Equivalent                         |
| ----------------------------------------------- | ---------------------------------------------- |
| `useSession()` from `next-auth/react`           | `useSession()` from `@/lib/auth-client`        |
| `session.status === "loading"`                  | `isPending === true`                           |
| `session.status === "authenticated"`            | `!isPending && !!session?.user`                |
| `session.status === "unauthenticated"`          | `!isPending && !session?.user`                 |
| `session.user.id`                               | `session.user.id`                              |
| `session.accessToken`                           | `session.session.token`                        |
| `signIn("credentials", { username, password })` | `signIn.email({ email, password })`            |
| `signOut({ callbackUrl })`                      | `signOut()` + `router.push()`                  |
| `auth()` server-side                            | `auth.api.getSession({ headers })`             |
| `SessionProvider` wrapper                       | Not needed — Better Auth uses cookies directly |

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: JWT round-trip preserves user identity

_For any_ valid user ID string, signing a JWT with the auth server's HS256 secret and then verifying that JWT with the same secret SHALL produce a payload whose `sub` claim equals the original user ID.

**Validates: Requirements 4.5, 4.6, 4.8, 14.1, 14.3**

### Property 2: Auth hook faithfully extracts session data

_For any_ Better Auth session object containing a `user.id` and `session.token`, the `useAuthenticatedUserId` hook SHALL return an `AuthenticatedUser` with `userId` equal to `session.user.id` and `token` equal to `session.session.token`.

**Validates: Requirements 10.2, 10.3**

### Property 3: fetchWithAuth attaches Bearer token from session

_For any_ valid session token string returned by `auth.api.getSession()`, the `fetchWithAuth` function SHALL include an `Authorization` header with value `Bearer <token>` in the outgoing request.

**Validates: Requirements 11.2**

## Error Handling

### Authentication Errors

| Scenario                        | Behavior                                                                                                                       |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Invalid email/password on login | `signIn.email()` returns an error object. Login page displays "Invalid username or password."                                  |
| Session expired                 | `useSession()` returns no session data. Components redirect to `/login`. TanStack Query hooks are disabled (`enabled: false`). |
| Database connection failure     | Better Auth returns 500 on auth endpoints. Login page displays a generic error.                                                |
| Missing `BETTER_AUTH_SECRET`    | Better Auth throws at startup. Application fails to start with a clear error message.                                          |
| Missing `DATABASE_URL`          | `pg.Pool` connection fails. Better Auth throws at startup.                                                                     |

### API Call Errors

| Scenario                                  | Behavior                                                                                            |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------- |
| No session (server-side)                  | `fetchWithAuth` omits the Authorization header. API Gateway returns 401.                            |
| No session (client-side)                  | `useAuthenticatedUserId` returns `status: "unauthenticated"`. TanStack Query hooks are disabled.    |
| Expired JWT sent to API Gateway           | API Gateway returns 401. Client detects the error and redirects to `/login`.                        |
| `fetchWithAuth` receives non-200 response | Throws `Error("Request failed (${status}) for ${path}")` — same behavior as current implementation. |

### Database Safety

| Scenario                         | Behavior                                                          |
| -------------------------------- | ----------------------------------------------------------------- |
| Better Auth migration runs       | Only creates `ba_*` prefixed tables. Flyway tables are untouched. |
| Better Auth migration runs twice | Idempotent — `CREATE TABLE IF NOT EXISTS` semantics.              |
| Dev user seed runs twice         | Idempotent — `ON CONFLICT DO NOTHING` semantics.                  |

## Testing Strategy

### Unit Tests (Vitest)

Unit tests verify specific examples and edge cases. The existing test files are updated to mock `@/lib/auth-client` instead of `next-auth/react` and `@/lib/auth` instead of `@/auth`.

**Test files to update:**

- `useAuthenticatedUserId.test.ts` — Mock `useSession` from `@/lib/auth-client` with Better Auth session shape
- `usePortfolio.test.ts` — Mock `useSession` from `@/lib/auth-client` (same pattern, different import)
- `fetchWithAuth.test.ts` — Mock `@/lib/auth` with `auth.api.getSession` instead of `auth()`

**Key test cases (example-based):**

- Login page: successful login redirects to `/overview`, failed login shows error, button disabled during loading
- PortfolioPageContent: skeleton during loading, redirect when unauthenticated, render components when authenticated
- UserMenu: displays name/email, sign-out calls signOut and redirects, placeholder during loading
- useAuthenticatedUserId: returns correct status for loading/authenticated/unauthenticated states
- fetchWithAuth: attaches Bearer header, omits header when no session, throws on non-200

### Property-Based Tests (Vitest + fast-check)

Property-based tests verify universal properties across many generated inputs. Each property test runs a minimum of 100 iterations.

**Library:** `fast-check` (TypeScript-native, integrates with Vitest)

**Property tests to implement:**

1. **JWT round-trip** — Tag: `Feature: better-auth-migration, Property 1: JWT round-trip preserves user identity`
   - Generate random user ID strings (UUIDs)
   - Sign a JWT with HS256 using the test secret
   - Verify the JWT with the same secret
   - Assert `sub` claim equals the original user ID
   - Minimum 100 iterations

2. **Auth hook session extraction** — Tag: `Feature: better-auth-migration, Property 2: Auth hook faithfully extracts session data`
   - Generate random `{ userId: string, token: string }` pairs
   - Mock `useSession` to return a Better Auth session with those values
   - Call `useAuthenticatedUserId()`
   - Assert returned `userId` and `token` match the input
   - Minimum 100 iterations

3. **fetchWithAuth token passthrough** — Tag: `Feature: better-auth-migration, Property 3: fetchWithAuth attaches Bearer token from session`
   - Generate random JWT token strings
   - Mock `auth.api.getSession` to return a session with that token
   - Call `fetchWithAuth` with a mock fetch
   - Assert the `Authorization` header equals `Bearer <token>`
   - Minimum 100 iterations

### E2E Tests (Playwright)

E2E tests verify the full authentication flow through the real UI:

1. **Login flow** — Navigate to `/login`, fill credentials, submit, verify redirect to `/overview`
2. **Session persistence** — After login, navigate to other pages, verify session is maintained
3. **Sign-out flow** — Click sign out in UserMenu, verify redirect to `/login`
4. **API Gateway integration** — After login, verify portfolio data loads (proves JWT is accepted by API Gateway)

### Smoke Tests

Smoke tests verify one-time configuration and setup:

1. **NextAuth artifacts removed** — Verify no `next-auth` in package.json, no NextAuth files exist
2. **Better Auth installed** — Verify `better-auth` in package.json
3. **Environment variables** — Verify correct variables in `.env.local`
4. **Database tables** — Verify `ba_*` tables exist and Flyway tables are intact
5. **Dev user seeded** — Verify `ba_user` contains the dev user record
