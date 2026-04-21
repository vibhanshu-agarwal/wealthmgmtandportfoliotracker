# Implementation Plan: CloudFront Static Route 403 Fix

## Tasks

- [x] 1. Finalize bug spec and route mapping decisions
  - [x] 1.1 Add requirements document
  - [x] 1.2 Add design document
  - [x] 1.3 Add task checklist
  - [x] 1.4 Confirm rewrite target style (`/foo.html` vs `/foo/index.html`) against actual exported object layout

- [x] 2. Refactor CloudFront distribution to dual-origin model
  - [x] 2.1 Add/confirm S3 static origin in Terraform networking module
  - [x] 2.2 Add/confirm API origin in Terraform networking module
  - [x] 2.3 Ensure API custom header (`X-Origin-Verify`) is attached only to API origin
  - [x] 2.4 Add ordered cache behavior for `/api/*` -> API origin
  - [x] 2.5 Set default cache behavior `/*` -> S3 origin

- [x] 3. Add CloudFront Function URI rewrite
  - [x] 3.1 Create CloudFront Function resource in Terraform
  - [x] 3.2 Add function code with extensionless route rewrite logic
  - [x] 3.3 Exclude `/_next/*`, `/api/*`, and explicit-extension paths from rewrite
  - [x] 3.4 Associate function with default behavior at `viewer-request`

- [ ] 4. Preserve API and auth integrity
  - [ ] 4.1 Verify `/api/auth/login` still routes to API origin
  - [ ] 4.2 Verify authenticated `/api/portfolio/*` calls remain successful
  - [ ] 4.3 Verify `CloudFrontOriginVerifyFilter` expectations remain satisfied for API traffic

- [x] 5. Pipeline rollout (no manual console patching)
  - [x] 5.1 Ensure Terraform CI plan/apply includes CloudFront behavior + function changes
  - [x] 5.2 Ensure CloudFront invalidation runs after deploy
  - [x] 5.3 Capture plan/apply output artifacts for review/audit

- [ ] 6. Post-deploy verification
  - [ ] 6.1 Validate direct frontend route loads return 200 for key pages
  - [ ] 6.2 Validate static assets (`/_next/*`) return 200
  - [ ] 6.3 Validate API endpoints continue to return expected status codes
  - [ ] 6.4 Document observed before/after behavior for 403 route issue

## Verification Evidence Log

### 2026-04-17 (pre-rollout baseline + patch ready)

- Terraform patch applied for trailing slash fallback in CloudFront Function:
  - `/foo/` now rewrites to `/foo/index.html`
  - extensionless non-trailing paths still rewrite to `/foo.html`
  - `/api/*`, `/_next/*`, and explicit-extension paths remain excluded
- Live production probe results (current deployed infra):
  - `GET /` -> `200 OK`
  - `GET /index.html` -> `200 OK`
  - `GET /login` -> `403 Forbidden` (S3 AccessDenied via CloudFront)
  - `GET /portfolio` -> `403 Forbidden` (S3 AccessDenied via CloudFront)
  - `GET /settings` -> `403 Forbidden` (S3 AccessDenied via CloudFront)
  - `GET /_next/static/chunks/03~yq9q893hmn.js` -> `200 OK`
  - `POST /api/auth/login` via CloudFront -> `504 Gateway Timeout` (API origin not reachable from distribution at probe time)
- Pipeline rollout blocker:
  - Local repo contains `.github/workflows/terraform.yml`, but remote default branch does not expose this workflow (GitHub API 404 for `terraform.yml`).
  - Terraform apply + CloudFront invalidation cannot be confirmed from CI until the workflow file is present on the remote branch that runs Actions.
