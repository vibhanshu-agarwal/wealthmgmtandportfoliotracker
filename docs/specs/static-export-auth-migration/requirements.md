# Requirements Document

## Introduction

Phase 3 deploys the frontend as a static Next.js export (`output: "export"`) to S3 behind CloudFront. In this model, Next.js route handlers under `frontend/src/app/api/**` cannot exist because they require a server runtime. Authentication and token issuance must be handled by the Spring Boot backend, and the browser must call backend `/api/*` paths directly through CloudFront.

## Requirements

### Requirement 1: Remove Next.js API Runtime Dependency

**User Story:** As a platform engineer, I want the frontend build to be serverless-static compatible so that S3 hosting succeeds with `out/` artifacts.

#### Acceptance Criteria

1. The frontend SHALL not contain operational route handlers under `frontend/src/app/api/**`.
2. `next build` with `output: "export"` SHALL complete without route-handler export errors.
3. Frontend auth and data calls SHALL use relative `/api/*` paths so CloudFront can route to backend origins.

### Requirement 2: Backend-Owned Auth Token Issuance

**User Story:** As an application operator, I want Spring Boot to be the authority for login and JWT issuance so authentication does not depend on Next.js server features.

#### Acceptance Criteria

1. The backend SHALL expose `POST /api/auth/login` that validates credentials and returns an HS256 JWT payload.
2. The response SHALL include `token`, `userId`, `email`, and `name`.
3. API Gateway security SHALL permit `/api/auth/**` without requiring an existing bearer token.
4. JWTs issued by the login endpoint SHALL be accepted by the API Gateway resource-server validation path.

### Requirement 3: Frontend Session Wiring Without Better Auth Runtime

**User Story:** As a frontend developer, I want the app to keep authenticated state in browser storage so static-export pages can gate routes and send bearer tokens.

#### Acceptance Criteria

1. The frontend SHALL replace runtime `useSession`/BFF token exchange dependencies with a browser session module backed by `localStorage`.
2. Login UI SHALL call backend `POST /api/auth/login` and persist returned token/user identity in browser storage.
3. Sign-out SHALL clear browser session state and redirect to `/login`.
4. Hooks that call backend APIs SHALL read the stored JWT and attach `Authorization: Bearer <token>`.

### Requirement 4: Build and Deployment Compatibility

**User Story:** As a release engineer, I want frontend CI/CD to publish static export output correctly so S3 contains complete deployable assets.

#### Acceptance Criteria

1. Frontend build step SHALL continue to run `npm run build`.
2. Deployment SHALL sync `frontend/out/` to `s3://${S3_BUCKET_NAME}`.
3. Local verification SHALL confirm `frontend/out/` is generated after the migration.
