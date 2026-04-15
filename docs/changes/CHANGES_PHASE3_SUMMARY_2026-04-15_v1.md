# Phase 3 Changes — 2026-04-15 v1

## BFF JWT Token Exchange — Centralized `mintToken` Module

Extracts duplicated HS256 JWT signing logic from the BFF route handler (`/api/auth/jwt/route.ts`) and the server-side fetch helper (`fetchWithAuth.server.ts`) into a single `mintToken` function in `frontend/src/lib/auth/mintToken.ts`. The `jose` library is now imported in exactly one module. Both callers delegate all signing to `mintToken`, eliminating the risk of silent divergence in token claims, expiry, or algorithm.

Prior frontend changes: [CHANGES_PHASE3_SUMMARY_2026-04-13_v2.md](./CHANGES_PHASE3_SUMMARY_2026-04-13_v2.md)

---

## Summary

### 1. Centralized Token Minting Function

Created `frontend/src/lib/auth/mintToken.ts`:

- Exports `TokenUser` interface (`id`, `email`, `name`) — the minimal session shape needed for JWT claims.
- Exports `mintToken(user: TokenUser): Promise<string>` — reads `AUTH_JWT_SECRET` (fallback `BETTER_AUTH_SECRET`) from `process.env` at invocation time (not module load time), encodes as UTF-8, signs an HS256 JWT with `sub`, `email`, `name` claims, `iat` at current time, `exp` at 1 hour.
- Imports `"server-only"` to prevent accidental client-side bundling.
- `jose`'s `SignJWT` is imported only in this module — no other source file under `frontend/src/` imports it directly.

### 2. BFF Route Handler Refactor

Updated `frontend/src/app/api/auth/jwt/route.ts`:

- Removed `import { SignJWT } from "jose"` and the module-level `secret` constant.
- Added `import { mintToken } from "@/lib/auth/mintToken"`.
- Replaced the inline `new SignJWT(...)` chain with `await mintToken(session.user)`.
- Session reading, 401 guard, and response shape (`{ token, userId, email }`) unchanged.

### 3. Server-Side Fetch Helper Refactor

Updated `frontend/src/lib/api/fetchWithAuth.server.ts`:

- Removed `import { SignJWT } from "jose"` and the `getJwtSecret()` helper function.
- Added `import { mintToken } from "@/lib/auth/mintToken"`.
- Replaced the inline `new SignJWT(...)` chain with `await mintToken(session.user)`.
- Session reading, header construction, fetch call, and error handling unchanged.

### 4. Property-Based Tests (fast-check, 4 properties × 100 iterations)

Created `frontend/src/lib/auth/mintToken.test.ts` with four correctness properties:

- **Property 1: Mint-then-verify round trip** — For any valid `TokenUser`, minting a JWT and verifying it with `jwtVerify` using the same secret succeeds without error. Validates Requirements 1.1, 1.2, 5.2, 7.1.
- **Property 2: Claim preservation** — For any valid `TokenUser`, the decoded JWT contains `sub === user.id`, `email === user.email`, `name === user.name`. Validates Requirements 1.4, 7.2, 7.3.
- **Property 3: Expiry invariant** — For any minted JWT, `exp - iat === 3600`. Validates Requirements 1.5, 1.6, 7.4.
- **Property 4: Algorithm header invariant** — For any minted JWT, `protectedHeader.alg === "HS256"`. Validates Requirements 1.7, 7.5.

### 5. Unit Tests

- **mintToken secret fallback** (2 tests) — Falls back to `BETTER_AUTH_SECRET` when `AUTH_JWT_SECRET` is unset; reads secret at invocation time, not module load time. Validates Requirements 1.2, 1.3, 5.1.
- **Route handler** (4 tests in `route.test.ts`) — Returns 200 with `{ token, userId, email }` for valid session; returns 401 when session missing; returns 401 when no user ID; calls `mintToken` (not inline SignJWT). Validates Requirements 2.2–2.5.
- **Server-side fetch helper** (4 updated tests in `fetchWithAuth.test.ts`) — Attaches `Authorization: Bearer` header from `mintToken` result; omits Authorization when no session; calls `mintToken` with session user; does not forward Cookie headers to backend. Validates Requirements 3.2–3.5, 6.1, 6.2.

### 6. Static Verification

- `route.ts` does not import from `jose`.
- `fetchWithAuth.server.ts` does not import from `jose`.
- `mintToken.ts` is the only file under `frontend/src/lib/` or `frontend/src/app/` that imports `SignJWT` from `jose`.

---

## Architectural Decisions

- **`TokenUser` interface over full session object** — `mintToken` accepts only the three fields it needs (`id`, `email`, `name`), not the entire Better Auth session. This makes the function easier to test and documents exactly which fields flow into the JWT.
- **Lazy secret resolution** — The secret is read from `process.env` inside the function body, not at module scope. Tests can stub environment variables between calls without module reloading.
- **`server-only` guard** — Prevents accidental import from Client Components, which would expose the signing secret in the client bundle.
- **No caching of the encoded secret** — `TextEncoder().encode()` runs on every invocation. The cost is negligible (microseconds) and guarantees the function always uses the current env value.

---

## Unchanged Components

- **Client-side fetch helper** (`fetchWithAuth.ts`) — Already accepts a raw JWT string. No signing logic.
- **TanStack Query hook** (`useAuthenticatedUserId.ts`) — Calls `GET /api/auth/jwt` and caches the response. Response shape unchanged.
- **API Gateway** — `JwtDecoderConfig` and `JwtAuthenticationFilter` continue to validate HS256 JWTs using `AUTH_JWT_SECRET`. JWT format is identical.
- **Backend microservices** — No changes. They receive the same `X-User-Id` header from the Gateway.

---

## Files Changed

| File                                                 | Change                                                                        |
| ---------------------------------------------------- | ----------------------------------------------------------------------------- |
| `frontend/src/lib/auth/mintToken.ts`                 | New — Centralized HS256 JWT minting function                                  |
| `frontend/src/lib/auth/mintToken.test.ts`            | New — 4 property-based tests + 2 unit tests                                   |
| `frontend/src/app/api/auth/jwt/route.ts`             | Modified — Delegates to `mintToken`, removed jose import                      |
| `frontend/src/app/api/auth/jwt/route.test.ts`        | New — 4 route handler unit tests                                              |
| `frontend/src/lib/api/fetchWithAuth.server.ts`       | Modified — Delegates to `mintToken`, removed jose import and `getJwtSecret()` |
| `frontend/src/lib/api/fetchWithAuth.test.ts`         | Modified — Server-side tests mock `mintToken` instead of jose                 |
| `.kiro/specs/bff-jwt-token-exchange/.config.kiro`    | New — Spec config                                                             |
| `.kiro/specs/bff-jwt-token-exchange/requirements.md` | New — 7 requirements with acceptance criteria                                 |
| `.kiro/specs/bff-jwt-token-exchange/design.md`       | New — Architecture, components, correctness properties                        |
| `.kiro/specs/bff-jwt-token-exchange/tasks.md`        | New — 7-task implementation plan                                              |

---

## Verification

- `npm run test` (frontend/) → 14 test files, 80 tests, 0 failures
- Static grep confirms `jose` imported only in `mintToken.ts`
- Commit: `bd6f67b` on `architecture/cloud-native-extraction`
