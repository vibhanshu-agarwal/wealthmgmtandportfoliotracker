# Infrastructure Fixes — Terraform State Drift & Style Hardening (2026-04-26)

**Previous revision:** [CHANGES_CACHE_WARMING_2026-04-25.md](./CHANGES_CACHE_WARMING_2026-04-25.md) — ARM64 flip + EventBridge warming module creation.

---

## Summary

Three Terraform CI failures were introduced and fixed across PRs #24–#26 after the warming infrastructure was reactivated. All failures were related to Terraform configuration, not application code:

1. **PR #24** — Warming reactivated; CI later failed due to a `null` validation panic.
2. **PR #25** — Fixed the validation panic; CI later failed due to a 409 conflict on Lambda permissions.
3. **PR #26** — Fixed the 409 conflict via `import` blocks; style hardening applied in the same session.

`terraform fmt -check` and `terraform validate` both pass locally as of this session. PR #26 is the current open PR and is **ready to merge**.

---

## 1. Root-Cause Chain

### 1.1 PR #24 — `null` Panic in `contains()` (Fixed in PR #25)

`variables.tf` used `contains(["arm64", "x86_64", null], var.lambda_architecture)` to allow a nullable default. Terraform evaluates `contains()` eagerly and panics when `null` appears as a list element or as the search value.

**Fix:** Replaced with `coalesce()` to resolve `null` to its semantic value before `contains()` sees it:

```hcl
condition = contains(["arm64", "x86_64"], coalesce(var.lambda_architecture, "arm64"))
```

**File:** `infrastructure/terraform/variables.tf` — `lambda_architecture` validation block.

### 1.2 PR #25 → PR #26 — 409 `ResourceConflictException` on Lambda Permissions

After PR #25 merged, `terraform apply` failed with:

```
Error: adding Lambda Permission (wealth-api-gateway/FunctionURLAllowInvokeAction):
ResourceConflictException: The statement id (FunctionURLAllowInvokeAction)
provided already exists.
```

**Root cause:** The `FunctionURLAllowInvokeAction` permission statements already existed on all 4 Lambda functions in AWS (created by an earlier apply or manually) but were absent from Terraform state. Every apply attempted to `AddPermission` and received HTTP 409.

**Fix:** Added Terraform `import` blocks to adopt the existing AWS resources into state without recreating them.

**First attempt (wrong location):** Import blocks were placed inside `modules/compute/main.tf`. Terraform rejected this at init:

```
Error: Import blocks are only allowed in the root module.
```

**Correct fix:** Blocks moved to the root `infrastructure/terraform/main.tf` using fully-qualified module addresses:

```hcl
import {
  to = module.compute.aws_lambda_permission.api_gateway_url_invoke
  id = "wealth-api-gateway:live/FunctionURLAllowInvokeAction"
}
# … repeated for portfolio, market_data, insight
```

Import ID format: `function_name:qualifier/statement_id`.

---

## 2. Style Hardening Applied (Same Session as PR #26)

The following style-guide violations were found and fixed to prevent future CI failures if linting is added:

| File | Change |
|------|--------|
| `modules/compute/variables.tf` | Added `description` to all 26 variables (style guide: required alongside `type`) |
| `versions.tf` | Consolidated `backend "s3"` block here; was split across `main.tf` |
| `providers.tf` *(new)* | Extracted `provider "aws"` block from `main.tf` (style guide: separate file) |
| `main.tf` | Now contains only: data source, module calls, and import blocks |

`terraform fmt -check -recursive` — ✅ exit 0
`terraform validate` — ✅ "The configuration is valid."

---

## 3. Files Modified in PR #26

| File | Type | Description |
|------|------|-------------|
| `infrastructure/terraform/main.tf` | Modified | Added 4 root-level `import` blocks; removed provider + backend blocks |
| `infrastructure/terraform/versions.tf` | Modified | Added `backend "s3"` block (moved from `main.tf`) |
| `infrastructure/terraform/providers.tf` | **New** | `provider "aws"` block (extracted from `main.tf`) |
| `infrastructure/terraform/modules/compute/main.tf` | Modified | Import blocks removed (were added here in error; reverted) |
| `infrastructure/terraform/modules/compute/variables.tf` | Modified | `description` added to all 26 variables |

---

## 4. What Was Not Changed

| Item | Reason |
|------|--------|
| AWS provider `~> 5.0` (not upgraded to `~> 6.0`) | v6 has breaking resource-schema changes; separate upgrade PR needed |
| `versions.tf` not renamed to `terraform.tf` | Pure cosmetic rename; no CI impact |
| `dynamodb_table` backend parameter (deprecated in TF 1.10+) | Warning only in CI (`~> 1.6`); migration to `use_lockfile = true` is a separate state-locking change |
| No Terraform test files added | Separate task; see handoff below |

---

## 5. Pinned Infrastructure Facts

| Fact | Value |
|------|-------|
| AWS Account | `844479804897` |
| AWS Region | `ap-south-1` |
| Terraform state bucket | `vibhanshu-tf-state-2026` |
| DynamoDB lock table | `vibhanshu-terraform-locks` |
| Lambda functions warmed | `wealth-api-gateway`, `wealth-portfolio-service`, `wealth-market-data-service`, `wealth-insight-service` |
| Lambda alias used for FURL + permissions | `live` |
| AWS provider version (locked) | `5.100.0` (pinned in `.terraform.lock.hcl`) |
| Terraform version (local) | `1.14.9` |
| Terraform version (CI) | `~> 1.6` via `hashicorp/setup-terraform@v3` |

---

## 6. Handoff

### 6.1 Current State

- **Open PR:** [#26 — fix/lambda-permission-import-drift](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/26)
- **Branch:** `fix/lambda-permission-import-drift` (3 commits ahead of `main`)
- **Status:** ✅ Ready to merge. `terraform fmt` and `terraform validate` both pass.
- **CI will:** run `terraform plan` (which imports the 4 existing permissions into state as no-ops), then `terraform apply` (which applies the plan without hitting 409), then verifies 4 EventBridge rules + 4 API destinations if `TF_VAR_ENABLE_WARMING=true`.

### 6.2 What Is Fixed

- [x] Terraform validation panic (`contains()` with `null`) — `coalesce()` guard
- [x] 409 `ResourceConflictException` on `FunctionURLAllowInvokeAction` — `import` blocks in root module
- [x] Import blocks incorrectly placed in child module — moved to root
- [x] All compute module variables missing `description` — added
- [x] `provider "aws"` and `backend "s3"` scattered across `main.tf` — extracted to `providers.tf` / `versions.tf`

### 6.3 What Remains (Next Agent)

| Priority | Task | Notes |
|----------|------|-------|
| **High** | Merge PR #26 | Already pushed and validated |
| **Medium** | Migrate S3 backend from `dynamodb_table` to `use_lockfile = true` | `dynamodb_table` is deprecated in TF ≥ 1.10; warning only in CI today since CI uses `~> 1.6`. Becomes an error in a future TF version. |
| **Medium** | Pin CI Terraform version to a specific minor (`1.6.x` → `1.9.x` or `1.14.x`) | `~> 1.6` resolves differently depending on setup-terraform version; local is 1.14.9, CI may differ |
| **Low** | Upgrade AWS provider `~> 5.0` → `~> 6.0` | Check breaking changes in the 6.x changelog first; run `terraform plan` after lock file update |
| **Low** | Add Terraform unit tests (`.tftest.hcl`) | Style guide: `tests/` directory with `*_unit_test.tftest.hcl` using `command = plan`; test warming enable/disable toggle and lambda_architecture validation rules |
| **Low** | Rename `versions.tf` → `terraform.tf` | HashiCorp style convention; purely cosmetic, no CI impact |

### 6.4 First Actions for the Next Agent

1. Confirm PR #26 has been merged: `git fetch origin && git checkout main && git pull`
2. Verify `terraform validate` still passes on `main` after merge.
3. Check whether CI run on merge commit succeeded (especially the warming smoke-check step).
4. If `TF_VAR_ENABLE_WARMING` is `true` in GitHub secrets, confirm SNS email confirmation has been clicked — alerts will not be delivered without it.
5. Pick up next task from §6.3 above.

### 6.5 Key Rules Learned This Session (Do Not Repeat These Mistakes)

- **`import` blocks must always live in the root module.** Placing them in a child module causes `terraform init` to fail with "Import blocks are only allowed in the root module." Use module-qualified addresses: `module.<name>.<resource_type>.<resource_name>`.
- **`contains()` panics on `null`.** Always wrap nullable variables with `coalesce(var.x, "default")` before passing them to `contains()`.
- **Never merge a PR before confirming the fix commit is pushed.** Verify with `git log --oneline origin/main..HEAD` before telling the user to merge.

---

## 7. Addendum — AWS Demo Auth + Main CI Hardening (2026-04-26)

### 7.1 PR #29 — AWS Demo Auth and Live Smoke Validation

- **PR:** [#29 — Fix AWS demo auth and live smoke validation](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/29)
- **State:** Merged to `main` as `91414d5`.
- **Branch:** `fix/mongo-health-seeding-retries`
- **Main purpose:** Make the AWS/demo path consistent around HS256 JWT validation, Terraform-owned auth env vars, canonical E2E user identity, and better frontend login diagnostics.

Key implementation context:

- API Gateway AWS/local JWT validation now uses HS256 via `AUTH_JWT_SECRET` instead of the earlier RS256/JWK path for the demo environment.
- `AUTH_JWT_SECRET` must be at least 32 characters for the `aws` profile.
- Canonical demo/E2E user ID is `00000000-0000-0000-0000-000000000e2e`.
- E2E seed ownership was aligned to user ID instead of email.
- Frontend login failures now preserve and surface better status-specific diagnostics.
- Opt-in live smoke coverage was added; the live site may still return `401 Invalid username or password` until deployed Lambda env vars and demo credentials are aligned.

Validation performed before/around PR #29:

- Terraform format/validate and preservation pytest were run successfully.
- API Gateway targeted fast/integration tests were run, including the AWS JWT integration path.
- Frontend auth/session tests, targeted ESLint, and Playwright discovery were run.
- PR follow-up fixed `PreservationPropertyTest` after `JwtDecoderConfig` refactoring by reflecting on the current `hmacJwtDecoder(String)` method and its `local`/`aws` profile annotation.

### 7.2 PR #30 — Main CI Secret Scan and Docker Build Flake

- **PR:** [#30 — Fix main CI secret scan and Docker RIE build flake](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/30)
- **State:** Merged on 2026-04-26 at 15:06:57 UTC.
- **Head commit before merge:** `1546269 Harden CI secret scan and Docker RIE handling`
- **Branch:** `fix/mongo-health-seeding-retries`

Two main-branch CI failures were fixed:

1. **Gitleaks failure on `main`**
   - Root cause: the earlier `.gitleaksignore` entry was commit-specific. After merge/squash behavior changed the commit SHA, the fingerprint no longer matched.
   - Fix: `infrastructure/terraform/terraform.tfvars.example` now leaves `auth_jwt_secret` blank instead of using a long secret-like placeholder.
   - Real values must continue to come from `TF_VAR_auth_jwt_secret` / GitHub Actions secrets, not from committed example files.

2. **Docker build timeout downloading `aws-lambda-rie`**
   - Root cause: `api-gateway`, `portfolio-service`, and `insight-service` Docker builds downloaded the AWS Lambda Runtime Interface Emulator from `github.com` during image build.
   - CI failed with `curl: (28) Failed to connect to github.com port 443` after ~135 seconds.
   - Fix: removed build-time `aws-lambda-rie` downloads and removed runtime `tar`/`gzip` installs where they were only needed for that download.
   - Lambda Web Adapter usage remains unchanged via `public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`.

PR #30 CI status observed before/at merge:

- Gitleaks Scan — success
- Terraform Validate & Plan — success
- Frontend CI — success
- Java unit/integration checks — success
- Docker build verify — success
- Qodana for JVM — neutral/non-blocking
- Terraform Apply — skipped on PR, as expected

Local validation performed for PR #30:

- `git diff --check` passed.
- Verified no remaining `aws-lambda-rie`, RIE release URL, or `RIE_ARCH` references in the three service Dockerfiles.
- `docker buildx build --check` passed for `api-gateway/Dockerfile`, `portfolio-service/Dockerfile`, and `insight-service/Dockerfile`.
- Local `gitleaks` was not installed, so the authoritative Gitleaks validation was GitHub Actions, which passed.
- `terraform fmt` is not applicable to `.tfvars.example`; Terraform formats `.tf`, `.tfvars`, and `.tftest.hcl` files.

### 7.3 Current Open Items / Issues

| Priority | Item | Context |
|----------|------|---------|
| **High** | Confirm production/demo auth env alignment after deploy | Live smoke previously reached the site but failed `POST /api/auth/login` with HTTP 401. Verify `AUTH_JWT_SECRET`, issuer expectations, seeded demo user, and frontend credentials are aligned in AWS Lambda environment variables after Terraform apply/deploy. |
| **High** | Check `main` post-merge CI for PR #30 | PR #30 checks passed before merge. Next agent should fetch latest `origin/main` and confirm the merge-commit CI run also passed, especially Gitleaks and Docker build verify. |
| **Medium** | Re-run opt-in live API smoke after AWS env update | Live smoke is intentionally outside normal local Chromium suite. Re-run once deployed credentials/env are known-good. |
| **Medium** | Resolve local branch/main divergence carefully | At handoff time the workspace was still on `fix/mongo-health-seeding-retries`; local `main` had a separate merge commit (`b3692c4`) and `origin/main` should be fetched before any further branch work. Do not assume local `main` equals remote. |
| **Medium** | Backend lock migration | Existing note still applies: migrate S3 backend from deprecated `dynamodb_table` to `use_lockfile = true` in a separate, deliberate Terraform state-locking change. |
| **Low** | Add Terraform tests | Add `.tftest.hcl` plan tests for warming toggle and validation behavior when there is time. |
| **Low** | Provider/Terraform version modernization | Consider pinning CI Terraform to an explicit minor and separately evaluating AWS provider `~> 6.0` upgrade. |

### 7.4 Useful Handoff Context for the Next Agent

- Do **not** rely on commit-specific Gitleaks fingerprints for placeholder values; prefer clearing or changing the placeholder so it is not secret-shaped.
- Do **not** reintroduce build-time downloads from GitHub Releases inside Dockerfiles unless absolutely necessary; they make CI flaky and slow.
- `aws-lambda-rie` is useful for local Lambda emulation but is not required in these production images because the services run the Java JAR directly with Lambda Web Adapter.
- The branch name `fix/mongo-health-seeding-retries` was reused across PR #29 and PR #30. PR #29 had already been merged before the follow-up push; PR #30 was then created to carry the remaining branch changes and CI hardening.
- Local untracked artifacts were intentionally left untouched during this work: `.augment-audit-aws-readonly.py`, `docs/changes/CHANGES_DEDUP_SUMMARY_2026-04-26.md`, `docs/changes/HANDOFF_2026-04-25_config-deduplicate-implementation.md`, `infrastructure/terraform/warming.tfplan`, and `tf-apply*.log` / `tf-fail.log`.
- Before doing any new work, run `git fetch origin`, inspect `git status --short --branch`, and decide whether to continue from `main` or create a fresh branch.
