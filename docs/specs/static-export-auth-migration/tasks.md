# Implementation Plan: Static Export Auth Migration

## Tasks

- [ ] 1. Create spec and migration guardrails
  - [x] 1.1 Add requirements document
  - [x] 1.2 Add design document
  - [x] 1.3 Add task checklist

- [ ] 2. Remove Next.js API route handlers
  - [ ] 2.1 Delete `frontend/src/app/api/auth/[...all]/route.ts`
  - [ ] 2.2 Delete `frontend/src/app/api/auth/jwt/route.ts`
  - [ ] 2.3 Delete `frontend/src/app/api/auth/jwt/health/route.ts`
  - [ ] 2.4 Delete related route tests in `frontend/src/app/api/auth/**`

- [ ] 3. Add backend auth endpoint in API Gateway
  - [ ] 3.1 Add DTOs for login request/response
  - [ ] 3.2 Add JWT signer utility (HS256, 1h exp)
  - [ ] 3.3 Add `POST /api/auth/login` controller
  - [ ] 3.4 Add auth bootstrap properties and env mapping
  - [ ] 3.5 Update security config to permit `/api/auth/**`

- [ ] 4. Rewire frontend auth/session runtime
  - [ ] 4.1 Add browser session module (`localStorage` persistence + hook)
  - [ ] 4.2 Update login page to call backend login endpoint
  - [ ] 4.3 Update route-gated components to use new session hook
  - [ ] 4.4 Update `UserMenu` sign-out to clear local session
  - [ ] 4.5 Update `useAuthenticatedUserId` to read stored JWT

- [ ] 5. Static-export compatibility cleanup
  - [ ] 5.1 Remove server-only session checks from dashboard pages where needed
  - [ ] 5.2 Ensure no runtime imports from deleted Next API auth layer

- [ ] 6. Verification
  - [ ] 6.1 Run `npm run build` in `frontend/`
  - [ ] 6.2 Confirm `frontend/out/` exists
  - [ ] 6.3 Summarize auth wiring changes and residual hardening items
