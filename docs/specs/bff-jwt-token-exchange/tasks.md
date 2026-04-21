# Implementation Plan: BFF JWT Token Exchange

## Overview

Centralize HS256 JWT minting into a single `mintToken` function in `frontend/src/lib/auth/mintToken.ts`. Refactor the BFF route handler and server-side fetch helper to delegate all signing to this function, then remove their direct `jose` imports. Validate correctness with property-based tests and unit tests.

## Tasks

- [x] 1. Create the centralized `mintToken` module
  - [x] 1.1 Create `frontend/src/lib/auth/mintToken.ts`
    - Add `import "server-only"` guard to prevent client-side bundling
    - Import `SignJWT` from `jose`
    - Export the `TokenUser` interface with `id`, `email`, and `name` fields
    - Implement the `mintToken(user: TokenUser): Promise<string>` function
    - Read `AUTH_JWT_SECRET` (fallback `BETTER_AUTH_SECRET`) from `process.env` at invocation time, not module load time
    - Encode the secret as UTF-8 bytes via `TextEncoder`
    - Set `sub`, `email`, `name` claims from the user object
    - Set protected header `alg` to `HS256`, `iat` to current time, `exp` to 1 hour
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 5.1, 5.2_

  - [x] 1.2 Write property tests for `mintToken` (Property 1: Mint-then-verify round trip)
    - **Property 1: Mint-then-verify round trip**
    - Generate random `TokenUser` objects using `fc.record({ id: fc.uuid(), email: fc.emailAddress(), name: fc.string({ minLength: 1 }) })`
    - For each generated user, call `mintToken`, then verify the result with `jwtVerify` using the same secret
    - Assert verification succeeds without error
    - Use `fast-check` with minimum 100 iterations
    - Test file: `frontend/src/lib/auth/mintToken.test.ts`
    - **Validates: Requirements 1.1, 1.2, 5.2, 7.1**

  - [x] 1.3 Write property tests for `mintToken` (Property 2: Claim preservation)
    - **Property 2: Claim preservation**
    - For each generated `TokenUser`, mint a JWT and decode the payload
    - Assert `sub` equals `user.id`, `email` equals `user.email`, `name` equals `user.name`
    - Test file: `frontend/src/lib/auth/mintToken.test.ts`
    - **Validates: Requirements 1.4, 7.2, 7.3**

  - [x] 1.4 Write property tests for `mintToken` (Property 3: Expiry invariant)
    - **Property 3: Expiry invariant**
    - For each generated `TokenUser`, mint a JWT and decode the payload
    - Assert `exp - iat === 3600`
    - Test file: `frontend/src/lib/auth/mintToken.test.ts`
    - **Validates: Requirements 1.5, 1.6, 7.4**

  - [x] 1.5 Write property tests for `mintToken` (Property 4: Algorithm header invariant)
    - **Property 4: Algorithm header invariant**
    - For each generated `TokenUser`, mint a JWT and decode the protected header
    - Assert `alg` equals `"HS256"`
    - Test file: `frontend/src/lib/auth/mintToken.test.ts`
    - **Validates: Requirements 1.7, 7.5**

  - [x] 1.6 Write unit tests for `mintToken` secret fallback behavior
    - Test that `mintToken` falls back to `BETTER_AUTH_SECRET` when `AUTH_JWT_SECRET` is unset
    - Test that `mintToken` reads the secret at invocation time (change env between calls, verify different secrets produce different JWTs)
    - Test file: `frontend/src/lib/auth/mintToken.test.ts`
    - _Requirements: 1.2, 1.3, 5.1_

- [x] 2. Checkpoint — Verify `mintToken` module
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Refactor BFF route handler to use `mintToken`
  - [x] 3.1 Update `frontend/src/app/api/auth/jwt/route.ts`
    - Remove `import { SignJWT } from "jose"`
    - Remove the module-level `secret` constant
    - Add `import { mintToken } from "@/lib/auth/mintToken"`
    - Replace the inline `new SignJWT(...)` chain with `await mintToken(session.user)`
    - Keep session reading, 401 guard, and response shape (`{ token, userId, email }`) unchanged
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 3.2 Write unit tests for the refactored route handler
    - Create `frontend/src/app/api/auth/jwt/route.test.ts`
    - Mock `@/lib/auth` and `@/lib/auth/mintToken`
    - Test: returns 200 with `{ token, userId, email }` for a valid session
    - Test: returns 401 when session is missing
    - Test: returns 401 when session has no user ID
    - Test: calls `mintToken` (verify the mock was invoked, not inline SignJWT)
    - _Requirements: 2.2, 2.3, 2.4, 2.5_

- [x] 4. Refactor server-side fetch helper to use `mintToken`
  - [x] 4.1 Update `frontend/src/lib/api/fetchWithAuth.server.ts`
    - Remove `import { SignJWT } from "jose"`
    - Remove the `getJwtSecret()` helper function
    - Add `import { mintToken } from "@/lib/auth/mintToken"`
    - Replace the inline `new SignJWT(...)` chain with `await mintToken(session.user)`
    - Keep session reading, header construction, fetch call, and error handling unchanged
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 4.2 Update unit tests for the server-side fetch helper
    - Update `frontend/src/lib/api/fetchWithAuth.test.ts`
    - Replace the `jose` mock with a mock for `@/lib/auth/mintToken`
    - Test: attaches `Authorization: Bearer` header from `mintToken` result when session exists
    - Test: omits Authorization header when no session exists
    - Test: calls `mintToken` (verify the mock was invoked, not inline SignJWT)
    - Test: does not forward Cookie headers to backend (only Content-Type and Authorization)
    - _Requirements: 3.2, 3.3, 3.4, 3.5, 6.1, 6.2_

- [x] 5. Checkpoint — Verify all refactored modules
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Static verification — Confirm `jose` is no longer imported outside `mintToken`
  - [x] 6.1 Verify no direct `jose` imports in refactored files
    - Confirm `frontend/src/app/api/auth/jwt/route.ts` does NOT import from `jose`
    - Confirm `frontend/src/lib/api/fetchWithAuth.server.ts` does NOT import from `jose`
    - Confirm `frontend/src/lib/auth/mintToken.ts` is the only file under `frontend/src/lib/` or `frontend/src/app/` that imports `SignJWT` from `jose`
    - _Requirements: 2.5, 3.5, 3.6_

- [x] 7. Final checkpoint — Full test suite
  - Run `npm run test` from `frontend/` to ensure all existing tests still pass alongside the new tests.
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document using `fast-check` (already a project dependency)
- Unit tests validate specific examples and edge cases
- The client-side fetch helper (`fetchWithAuth.ts`) and TanStack Query hook (`useAuthenticatedUserId.ts`) require no changes — they are unaffected by this refactor
- The API Gateway is unchanged — the JWT format produced by `mintToken` is identical to what both callers produced before
