<!-- 96dd9484-7c6a-4744-a647-78a004b136a0 -->
---
todos:
  - id: "import-cf-distribution"
    content: "Wire domain_name/acm_certificate_arn/route53_zone_id into terraform.yml and `terraform import` the existing CloudFront distribution + Route53 record for vibhanshu-ai-portfolio.dev into state"
    status: pending
  - id: "tf-apply-rewrite"
    content: "terraform apply so the live distribution gains the viewer-request CF function, /api/* ordered behavior with X-Origin-Verify, and default_root_object=\"\"; invalidate /* after apply"
    status: pending
  - id: "fix-branch-condition"
    content: "Add architecture/cloud-native-extraction to terraform.yml `apply` job condition (or merge branch to main) so infra changes ship"
    status: pending
  - id: "delete-legacy-module"
    content: "Delete orphaned infrastructure/terraform/modules/networking/ duplicate module"
    status: pending
  - id: "cors-prod"
    content: "Externalise api-gateway CORS allow-list as a List<String> via app.cors.allowed-origin-patterns (ConfigurationProperties), switch SecurityConfig to setAllowedOriginPatterns, and set both the apex and a preview wildcard (https://vibhanshu-ai-portfolio.dev, https://*.vibhanshu-ai-portfolio.dev) in application-prod.yml"
    status: pending
  - id: "jwt-filter-skip-auth"
    content: "Extend JwtAuthenticationFilter skip list to include /api/auth/** so permitAll login endpoint is not rejected with 401"
    status: pending
  - id: "verify-origin-secret"
    content: "Confirm CLOUDFRONT_ORIGIN_SECRET value is identical across GitHub secret, Lambda env, and CloudFront custom_header; reapply TF if needed"
    status: pending
  - id: "post-deploy-verification"
    content: "Probe /login /overview /portfolio /market-data /settings all return 200, POST /api/auth/login returns 200, authenticated API calls succeed, direct Function URL without X-Origin-Verify still returns 403"
    status: pending
isProject: false
---
## Root Cause Analysis

### Symptom 1 — `GET /login` returns "Access Denied"

Request flow on production:

```mermaid
flowchart LR
    Browser -->|GET /login| CF[CloudFront distribution for vibhanshu-ai-portfolio.dev]
    CF -->|default behavior| S3[S3 static bucket]
    S3 -->|key "login" does not exist; OAC grants only s3:GetObject| AccessDenied[403 AccessDenied]
```

- `frontend/next.config.ts` sets `output: "export"`, so the build emits `frontend/out/login.html` (not `out/login/index.html`). Confirmed in `frontend/out/` — `login.html` exists (12 123 bytes); there is no `out/login/index.html`.
- The deploy step `aws s3 sync ./frontend/out s3://… --delete` in `.github/workflows/deploy.yml` uploads `login.html` at key `login.html`, not `login`.
- The CloudFront module `infrastructure/terraform/modules/cdn/main.tf` does define a viewer-request function that rewrites `/login` → `/login.html`, but on the distribution actually serving `vibhanshu-ai-portfolio.dev` the rewrite is not active. Evidence:
  - `.kiro/specs/cloudfront-static-route-403-fix/tasks.md` (2026‑04‑17 probe) shows `GET /` → 200 but `GET /login`, `/portfolio`, `/settings` all still return 403, AND the task list item "Pipeline rollout" is marked done but post‑deploy verification (item 6) is not — the workflow file was not on remote default branch at that time.
  - `/` returning 200 while `/login` 403 is consistent with the distribution still having `default_root_object = "index.html"` (or an older version of the CF Function) and no working rewrite for non-root paths.
  - `.github/workflows/terraform.yml` `apply` job is gated on `github.ref == 'refs/heads/main'`, but current working branch is `architecture/cloud-native-extraction`. Terraform infrastructure changes made on the feature branch are not being applied.
  - The same terraform.yml `plan`/`apply` command does **not** pass `-var="domain_name=…"` / `acm_certificate_arn` / `route53_zone_id`, so the TF‑managed distribution gets `aliases = []` and cannot be the one the custom domain is pointing at. The custom domain therefore resolves to a **different**, manually-created CloudFront distribution whose config has never been updated by the new Terraform module.
- There is also a legacy `infrastructure/terraform/modules/networking/` module defining identically named resources (`aws_cloudfront_function.static_route_rewrite`, `aws_cloudfront_origin_access_control.static_s3`, `aws_cloudfront_distribution.main`) that is no longer referenced from root `main.tf` (root calls `./modules/cdn` under alias `module "networking"`). This dead module is a drift/confusion hazard.

### Symptom 2 — `POST /api/auth/login` returns 403 in the browser console

Relevant code: `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`

```39:51:api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(
                List.of("http://localhost:3000", "http://127.0.0.1:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
```

- The browser fetch is to `apiPath("/auth/login")` which, with no `NEXT_PUBLIC_API_BASE_URL` (intentionally stripped from CI — `frontend/.gitignore` excludes `.env*.local`), resolves to the relative path `/api/auth/login`.
- CloudFront routes `/api/*` to the api-gateway Lambda Function URL with an injected `Origin: https://vibhanshu-ai-portfolio.dev` header.
- Spring WebFlux CORS runs before Spring Security's authorization and sees `Origin` not in `{localhost:3000, 127.0.0.1:3000}` → responds **403 Forbidden**. That is exactly the console error.
- `application-prod.yml` / `application-aws.yml` do not override the hard-coded allow-list, so production inherits the local-dev list.

### Symptom 3 (latent, will surface once CORS is fixed) — `/api/auth/**` still blocked by JwtAuthenticationFilter

`JwtAuthenticationFilter` runs at `HIGHEST_PRECEDENCE + 2` and only skips principal extraction for `/actuator/**` and `/api/portfolio/health`:

```32:42:api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java
        String path = exchange.getRequest().getURI().getPath();

        // Skip JWT processing for paths that are permitAll() in SecurityConfig.
        // These paths have no principal — the filter must not reject them.
        if (path.startsWith("/actuator") || path.equals("/api/portfolio/health")) {
```

`/api/auth/**` is `permitAll()` in `SecurityConfig`, so no principal is ever set. The filter's `switchIfEmpty` path sets status **401** and breaks the response. With CORS currently failing first, this is masked, but it will become the next failure.

### Symptom 4 (verify) — `X-Origin-Verify` secret mismatch

`CloudFrontOriginVerifyFilter` returns 403 when `System.getenv("CLOUDFRONT_ORIGIN_SECRET")` is set but the header value differs. The secret is wired end-to-end in Terraform (`modules/cdn/main.tf` custom_header → `modules/compute/main.tf` env var `CLOUDFRONT_ORIGIN_SECRET`), but only works if both the CF distribution and the Lambda have been applied from the same `TF_VAR_cloudfront_origin_secret`. If the live custom-domain CF distribution is the older manual one, it is not injecting `X-Origin-Verify` at all — so the filter (which rejects absent-header when a secret is configured) would 403 every request. This is a likely second contributor to the login 403.

## Remediation Plan

The fix has three independent work streams; order matters only for verification.

### A. Bring the public CloudFront distribution under Terraform and enable the rewrite

Goal: the distribution actually fronting `vibhanshu-ai-portfolio.dev` must have both the viewer-request function and the `X-Origin-Verify` custom header on the `/api/*` origin.

**Canonical path: import the existing distribution into Terraform state** (no DNS cutover, no alias-conflict risk). Replace-and-repoint is *not* a parallel option because CloudFront will reject a second distribution that claims the same alias until the manual one is disassociated — we'd incur a `CNAMEAlreadyExists` stall in the middle of remediation.

Steps:

1. Set `TF_VAR_domain_name=vibhanshu-ai-portfolio.dev`, `TF_VAR_acm_certificate_arn`, `TF_VAR_route53_zone_id` in `.github/workflows/terraform.yml` (add `-var` lines in both the plan and apply jobs).
2. `terraform import 'module.networking.aws_cloudfront_distribution.main' <existing-distribution-id>`.
3. Also import the Route 53 A-alias if it is manually managed.
4. `terraform apply` — Terraform updates the distribution in place: attaches the CF Function at viewer-request on the default behavior (the function already maps `/` → `/index.html` explicitly, so `default_root_object = ""` is safe), adds the `/api/*` ordered behavior with `X-Origin-Verify`, and sets `default_root_object = ""`.

> **Escape hatch (do not take by default):** if `terraform import` surfaces unreconcilable drift (e.g. origin shapes that can't be adopted without destructive field changes), fall back to creating a *new* distribution under Terraform, then cut DNS over in a single Route 53 change only after the old distribution's alias has been cleared. This avoids the `CNAMEAlreadyExists` trap.

### B. Unblock the deployment pipeline so infra changes actually ship

- Add `architecture/cloud-native-extraction` to the `apply` job condition in `.github/workflows/terraform.yml` (currently only `main`), **or** merge the branch to `main` before attempting remediation.
- Add the three missing `-var` flags (domain_name / acm_certificate_arn / route53_zone_id) to both the plan and apply steps.
- Delete the orphan legacy module `infrastructure/terraform/modules/networking/` to stop confusing future readers — root `main.tf` already uses `./modules/cdn`.
- After apply: `aws cloudfront create-invalidation --distribution-id <id> --paths "/*"` (the workflow already does this post-apply, but only when the distribution id is in state — which requires A above).

### C. Fix the api-gateway auth path

1. Externalise and widen the CORS allow-list in `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`:
   - Bind a `List<String>` property (e.g. `app.cors.allowed-origin-patterns`) via `@ConfigurationProperties`; default still local-dev values (`http://localhost:3000`, `http://127.0.0.1:3000`).
   - Switch the `CorsConfiguration` from `setAllowedOrigins(...)` to `setAllowedOriginPatterns(...)` — this is required when `allowCredentials=true` and any entry contains a wildcard (e.g. preview subdomains), and it also accepts plain origins, so it subsumes the current behavior with no regression.
   - In `application-prod.yml`, provide a list that covers prod plus future preview environments, e.g.:
     ```yaml
     app:
       cors:
         allowed-origin-patterns:
           - https://vibhanshu-ai-portfolio.dev
           - https://*.vibhanshu-ai-portfolio.dev
     ```
     Optionally add the raw CloudFront default `https://d*.cloudfront.net` if direct-distribution access is required.
   - Keep `allowCredentials=true` and `allowedHeaders` as-is.
2. In `JwtAuthenticationFilter.filter`, extend the skip prefix check to include `/api/auth/` so `permitAll` paths are not rejected by the filter:
   - Change the guard to `if (path.startsWith("/actuator") || path.equals("/api/portfolio/health") || path.startsWith("/api/auth/"))`.
   - Keep the unconditional X-User-Id strip as-is for spoof prevention.
3. Re-verify `CLOUDFRONT_ORIGIN_SECRET` is identical across:
   - GitHub Actions secret `CLOUDFRONT_ORIGIN_SECRET` used by `TF_VAR_cloudfront_origin_secret` in terraform.yml,
   - Actual Lambda env var on `wealth-api-gateway`,
   - Custom header configured on the live CloudFront distribution.
   If mismatched, the symptom is uniformly 403 from `CloudFrontOriginVerifyFilter`. Reapply Terraform after import (step A) to align them.

### D. Post-fix verification (maps to unfinished tasks 4 and 6 in `.kiro/specs/cloudfront-static-route-403-fix/tasks.md`)

- `curl -I https://vibhanshu-ai-portfolio.dev/` → 200 (confirms the viewer-request function's `/` → `/index.html` branch is active after `default_root_object` was cleared; catches a silent function-association failure).
- Browser: `https://vibhanshu-ai-portfolio.dev/login`, `/overview`, `/portfolio`, `/market-data`, `/settings` all return 200 with the expected HTML.
- `curl -I https://vibhanshu-ai-portfolio.dev/_next/static/chunks/*.js` → 200 (assets untouched by rewrite).
- Browser DevTools Network: `POST /api/auth/login` with body `{"email":"dev@localhost.local","password":"password"}` → 200 and returns `{token,userId,email,name}` (the dev user in `AuthController`).
- Subsequent authenticated call (e.g. `GET /api/portfolio/…` with Bearer token) → 200, no 401/403 regression.
- Direct probe of the Lambda Function URL without the `X-Origin-Verify` header → 403 (confirms LURL security still enforced).

## Files likely to change

- `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java` — bind `app.cors.allowed-origin-patterns` (List<String>) via `@ConfigurationProperties`, switch to `setAllowedOriginPatterns`.
- `api-gateway/src/main/resources/application-prod.yml` — add `app.cors.allowed-origin-patterns` list (apex + `https://*.vibhanshu-ai-portfolio.dev`).
- `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java` — skip `/api/auth/**`.
- `.github/workflows/terraform.yml` — add `domain_name`/`acm_certificate_arn`/`route53_zone_id` vars; optionally widen `apply` branch condition.
- `infrastructure/terraform/modules/networking/` — delete (orphaned duplicate of `cdn/`).
- No changes needed in `frontend/src/**` or `frontend/next.config.ts`.