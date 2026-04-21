# Requirements Document

## Introduction

Production/static frontend routes are intermittently returning HTTP 403 for extensionless paths (for example `/portfolio`, `/settings`, `/login`) when served through CloudFront. This is consistent with S3 REST origin + OAC behavior where missing object keys return `AccessDenied` instead of `NotFound`.

The architecture must clearly separate static frontend delivery (S3 origin) from dynamic API delivery (API Gateway origin), and must ensure extensionless frontend paths are rewritten to valid static-export objects.

This bug fix must be delivered through Terraform + CI/CD pipeline changes only (no manual console edits).

## Requirements

### Requirement 1: Split CloudFront routing by concern

**User Story:** As a platform engineer, I want CloudFront to route static frontend and dynamic API requests to different origins so each request path resolves in the correct backend system.

#### Acceptance Criteria

1. CloudFront SHALL have a dedicated S3 origin for static frontend assets.
2. CloudFront SHALL have a dedicated API origin for backend/API requests.
3. CloudFront SHALL route `/api/*` requests to the API origin.
4. CloudFront default behavior (`/*`) SHALL route to the S3 origin.
5. API-only security headers (for example `X-Origin-Verify`) SHALL only be sent to the API origin.

### Requirement 2: Resolve extensionless frontend routes

**User Story:** As a user, I want direct navigation to app routes like `/portfolio` to load successfully without 403 errors.

#### Acceptance Criteria

1. CloudFront SHALL rewrite extensionless non-API paths to static-export object keys before origin fetch.
2. `/` SHALL resolve to `/index.html`.
3. `/foo` SHALL resolve to `/foo.html` (or `/foo/index.html`, depending on export layout decision), consistently across all routes.
4. Paths that already include file extensions SHALL NOT be rewritten.
5. Static framework assets (`/_next/*`) SHALL NOT be rewritten.

### Requirement 3: Preserve API behavior and security controls

**User Story:** As a backend operator, I want API endpoints to keep current auth and gateway protection while frontend routing is fixed.

#### Acceptance Criteria

1. `/api/auth/login` SHALL continue to be reachable through CloudFront.
2. Authenticated `/api/portfolio/*` requests SHALL continue to work with bearer tokens.
3. CloudFront origin verification control (header + gateway filter) SHALL remain effective for API origin traffic.
4. No frontend route rewrite SHALL alter `/api/*` request paths.

### Requirement 4: Pipeline-managed rollout and verification

**User Story:** As a release engineer, I want this bug fix deployed exclusively through IaC and CI/CD so infrastructure state remains auditable and reproducible.

#### Acceptance Criteria

1. Terraform module(s) SHALL contain all routing/rewrite changes.
2. No manual CloudFront console modifications SHALL be required.
3. Deployment pipeline SHALL apply the Terraform change and invalidate CloudFront cache.
4. Post-deploy verification SHALL include successful direct loads for `/`, `/login`, `/overview`, `/portfolio`, `/market-data`, `/ai-insights`, `/settings`.
5. Post-deploy verification SHALL include successful API requests on `/api/*` without new 403 regressions.
