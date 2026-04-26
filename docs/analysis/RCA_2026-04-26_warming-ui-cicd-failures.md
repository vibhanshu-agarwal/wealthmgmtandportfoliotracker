# Root Cause Analysis — Production Issues (2026-04-26)

**Author:** Kiro (AI-assisted analysis)  
**Date:** 2026-04-26  
**Status:** Deferred — warming work parked; see [docs/todos/backlog/eventbridge-not-working/README.md](../todos/backlog/eventbridge-not-working/README.md)
**Scope:** Three interrelated production issues reported after the config deduplication PR merge wave (PRs #12–#22)

---

## Executive Summary

Three production issues were reported on 2026-04-26:

1. **Cache warming events not generated** — EventBridge warming infrastructure destroyed
2. **UI sluggish and broken** — API calls returning 502 due to Lambda cold starts
3. **Terraform / CI/CD failure** — Stale variable in local `terraform.tfvars`

All three share a single root cause chain: the CI/CD Terraform workflow was never updated to pass `enable_warming=true`, so every CI-triggered `terraform apply` ran with the default (`false`), destroying the warming resources that had been created manually. Without warming, Lambda cold starts cascade through CloudFront's 60-second origin timeout, breaking the UI. A secondary issue — a stale variable left in the local (gitignored) `terraform.tfvars` — blocks local Terraform runs.

---

## Investigation Method

- Connected to AWS account `844479804897` in `ap-south-1` using credentials from `.env.secrets`
- Queried live AWS resources: EventBridge Rules, API Destinations, Connections, IAM Roles, SNS Topics, Lambda functions, CloudFront distributions, S3 buckets
- Pulled Terraform remote state from `s3://vibhanshu-tf-state-2026/terraform.tfstate`
- Cross-referenced against local Terraform config (`variables.tf`, `main.tf`, `terraform.tfvars`), CI workflow (`.github/workflows/terraform.yml`), and the four handoff/change documents
- Tested live endpoints: CloudFront frontend, CloudFront API path, direct Lambda Function URL
- Reviewed CloudWatch invocation metrics for the last 24 hours

---

## Issue 1: Cache Warming Events Not Being Generated

### Symptom

EventBridge is not sending `rate(5 minutes)` warming pings to the four Lambda functions. Cold starts persist across all services.

### Evidence

| Check | Result |
|---|---|
| `aws events list-rules --name-prefix wealth-warm` | `{ "Rules": [] }` |
| `aws events list-api-destinations --name-prefix wealth-warm` | `{ "ApiDestinations": [] }` |
| `aws events list-connections --name-prefix wealth` | `{ "Connections": [] }` |
| `aws iam get-role --role-name wealth-lambda-warming-scheduler` | `NoSuchEntity` |
| `aws sns list-topics` | `[]` |
| Terraform state modules | `compute`, `database`, `networking` only — no `warming` |
| CloudWatch `Invocations` for `wealth-api-gateway` (24h) | ~6/hour (CI/synthetic only, not the 12/hour expected from warming) |
| `terraform.tfvars` | `enable_warming = true` (local) |
| `variables.tf` | `enable_warming` default = `false` |

### Root Cause

The warming infrastructure was originally applied **manually from the local machine** on 2026-04-25 (documented in `CHANGES_CACHE_WARMING_2026-04-25.md`). The local `terraform.tfvars` has `enable_warming = true`, so the local apply created all resources successfully.

However, the CI/CD workflow (`.github/workflows/terraform.yml`) was **never updated** to pass `enable_warming=true`. The `terraform plan` step in CI uses only these explicit `-var=` flags:

```yaml
terraform plan -input=false -lock-timeout=10m \
  -var="state_bucket_name=..." \
  -var="lock_table_name=..." \
  -var="artifact_bucket_name=..." \
  -var="frontend_bucket_name=..." \
  -var="aws_region=..." \
  -var="enable_aws_managed_database=false" \
  -out=tfplan
```

No `-var="enable_warming=true"` and no `TF_VAR_enable_warming` environment variable. Since `enable_warming` defaults to `false` in `variables.tf`, every CI-triggered apply runs with `count = 0` on the warming module.

When the 11 dedup PRs merged into `main`, several touched `infrastructure/terraform/` (notably PR #17 which modified `variables.tf` and `main.tf`). Each merge triggered the Terraform CI workflow, which ran `terraform apply` with `enable_warming=false` — **destroying all warming resources** that had been created locally.

### Causal Chain

```
Local apply (2026-04-25)
  └─ enable_warming=true (from terraform.tfvars)
  └─ Creates: 4 EventBridge Rules, 4 API Destinations, 1 Connection, 1 IAM Role, 1 SNS Topic, 1 Alarm
       │
Dedup PRs merge to main (2026-04-26)
  └─ PR #17 touches infrastructure/terraform/ → triggers terraform.yml workflow
  └─ CI plan: enable_warming=false (default, not overridden)
  └─ CI apply: module.warming count = 0 → DESTROYS all warming resources
       │
Result: All warming infrastructure gone from AWS
```

---

## Issue 2: UI Sluggish and Broken

### Symptom

The frontend dashboard feels slow and broken. API calls fail or time out.

### Evidence

| Check | Result |
|---|---|
| CloudFront static content (`GET /`) | **200 OK**, 6,851 bytes, fast |
| S3 bucket `_next/static/` | 31 JS/CSS/font files present, all dated 2026-04-26 |
| CloudFront API (`GET /api/portfolio/health`) | **502 Bad Gateway** (consistent) |
| Direct Lambda FURL (`GET /actuator/health`) | **200 OK, 65ms** (when warm) |
| CloudFront `OriginReadTimeout` | 60 seconds |
| Lambda `wealth-api-gateway` state | `Active`, `Successful`, 2048 MB, arm64 |

### Root Cause

This is a **cascading effect of Issue 1**. The request path for API calls is:

```
Browser → CloudFront (/api/*) → wealth-api-gateway Lambda → downstream Lambda (portfolio/market-data/insight)
```

Without warming:
1. Both `wealth-api-gateway` and the downstream Lambda are cold
2. Cold start for each: ~9–10 seconds (arm64 baseline from `CHANGES_CACHE_WARMING_2026-04-25.md`)
3. Combined cold-start chain: ~20 seconds minimum, potentially longer with JVM class loading
4. CloudFront's `OriginReadTimeout` is 60 seconds — the first request may succeed, but subsequent requests to different downstream services may chain cold starts
5. CloudFront returns **502 Bad Gateway** when the origin fails to respond
6. The frontend's TanStack Query hooks receive 502s on all API calls → dashboard shows loading spinners or error states

The static frontend loads fine because it's served from S3 (separate CloudFront origin, `frontend-static-s3`). Only the `/api/*` path is affected.

**Note:** The 502 observed during investigation was consistent even after the Lambda was invoked directly (which warmed it). This suggests the CloudFront → Lambda path may have additional latency from TLS negotiation and the `CloudFrontOriginVerifyFilter` header validation, or that the downstream service (portfolio) was still cold.

### Why This Wasn't Visible Before

The warming infrastructure was keeping all four Lambdas warm with 5-minute pings. With warming destroyed, the Lambdas go cold after ~15 minutes of inactivity (AWS default), and every subsequent user visit triggers the full cold-start chain.

---

## Issue 3: Terraform and CI/CD Failure

### Symptom

Terraform plan/apply fails.

### Evidence

| Check | Result |
|---|---|
| `terraform.tfvars` line 24 | `s3_key_api_gateway = "api-gateway/api-gateway.jar"` |
| `variables.tf` | No `s3_key_api_gateway` declaration (removed by PR #17) |
| `main.tf` | No `s3_key_api_gateway` reference (removed by PR #17) |
| CI workflow `-var=` flags | No `s3_key_api_gateway` flag (removed by PR #17) |

### Root Cause

PR #17 (`config/dedup-06-terraform-variable-dedup`) correctly removed `s3_key_api_gateway` from:
- `infrastructure/terraform/variables.tf` (variable declaration)
- `infrastructure/terraform/main.tf` (module pass-through)
- `.github/workflows/terraform.yml` (`-var=` flag)
- `infrastructure/terraform/localstack.tfvars`
- `infrastructure/terraform/terraform.tfvars.example`
- PowerShell and shell scripts (`-var=` flags)

However, `terraform.tfvars` is **gitignored** (it contains production secrets). The PR could not remove the line from the user's local copy. The stale assignment remains:

```hcl
s3_key_api_gateway = "api-gateway/api-gateway.jar"
```

When running `terraform plan` locally with this tfvars file, Terraform errors:

```
Error: Value for undeclared variable "s3_key_api_gateway"
```

**CI is not affected by this specific issue** — the CI workflow doesn't use `terraform.tfvars` (it passes all values via `-var=` flags and `TF_VAR_*` environment variables). The CI failure is caused by Issue 1's root cause: the warming module being destroyed on every apply.

### Secondary CI Concern

The `S3_KEY_API_GATEWAY` GitHub Actions secret may still exist in the repository settings. Per the dedup handoff notes, it should be deleted after PR #17 merges. If it's still present, it's harmless (no workflow references it), but it's dead configuration that should be cleaned up.

---

## Dependency Graph

```
Issue 3 (stale tfvars)          Issue 1 (warming destroyed)
  │                                │
  │ blocks local terraform         │ causes cold Lambdas
  │                                │
  └──────────┐    ┌────────────────┘
             │    │
             ▼    ▼
        Issue 2 (UI broken)
        API calls → 502 via CloudFront
        due to Lambda cold-start timeout
```

---

## Fix Plan

### Priority Order

All three fixes are independent and can be applied in parallel, but the recommended order optimizes for fastest user-visible improvement.

### Fix 1: Remove stale variable from local `terraform.tfvars` (immediate)

**Risk:** None  
**Blast radius:** Local only

Delete line 24 from `infrastructure/terraform/terraform.tfvars`:

```diff
- s3_key_api_gateway   = "api-gateway/api-gateway.jar"
```

This unblocks local `terraform plan` and `terraform apply`.

### Fix 2: Add warming variables to CI workflow (5 minutes)

**Risk:** Low — additive change, no existing behavior modified  
**Blast radius:** CI workflow + AWS warming resources

Add to `.github/workflows/terraform.yml` in the top-level `env:` block:

```yaml
TF_VAR_enable_warming: "true"
TF_VAR_warming_alarm_email: ${{ secrets.WARMING_ALARM_EMAIL }}
```

Create GitHub Actions repository secret:

| Secret | Value |
|---|---|
| `WARMING_ALARM_EMAIL` | `vibhanshu.agarwal@gmail.com` |

**Alternative:** Instead of a secret, hardcode the email directly in the workflow if it's not sensitive:

```yaml
TF_VAR_enable_warming: "true"
TF_VAR_warming_alarm_email: "vibhanshu.agarwal@gmail.com"
```

### Fix 3: Push to main and verify (10 minutes)

**Risk:** Low — Terraform will recreate the warming resources  
**Blast radius:** AWS EventBridge, IAM, SNS, CloudWatch

1. Commit fixes 1 and 2
2. Push to `main` (or open a PR and merge)
3. CI runs `terraform apply` with `enable_warming=true`
4. Terraform recreates: 4 EventBridge Rules, 4 API Destinations, 1 Connection, 1 IAM Role, 1 SNS Topic, 1 CloudWatch Alarm
5. Expected apply output: `Resources: ~12 added, 0 changed, 0 destroyed`

### Fix 4: Post-apply verification (5 minutes after apply)

1. **Confirm SNS subscription** — click the "Confirm subscription" link in the email sent to the warming alarm address
2. **Verify EventBridge Rules:**
   ```bash
   aws events list-rules --name-prefix wealth-warm --region ap-south-1
   ```
   Expected: 4 rules, all `ENABLED`, `rate(5 minutes)`
3. **Verify warming hits** — after ~5 minutes, check CloudWatch Logs for each Lambda for `GET /actuator/health` requests
4. **Verify UI** — load `https://d1t9eh6t95r2m3.cloudfront.net/` and confirm API calls succeed without 502s

### Fix 5: Cleanup (1 minute)

Delete the `S3_KEY_API_GATEWAY` GitHub Actions secret from **GitHub → Settings → Secrets and variables → Actions**. It is no longer referenced by any workflow after PR #17.

---

## Prevention Recommendations

### Short-term

1. **Add a CI smoke test** after `terraform apply` that verifies warming resources exist when `enable_warming=true`:
   ```bash
   RULE_COUNT=$(aws events list-rules --name-prefix wealth-warm --query 'length(Rules)' --output text)
   if [ "$RULE_COUNT" -ne 4 ]; then echo "FAIL: Expected 4 warming rules, found $RULE_COUNT"; exit 1; fi
   ```

2. **Document the warming variables** in the Terraform CI workflow with a comment explaining why they must be kept in sync with `terraform.tfvars`.

### Medium-term

3. **Consolidate Terraform variable injection** — the current split between `-var=` flags (in the plan step) and `TF_VAR_*` env vars (in the env block) is error-prone. Consider moving all variables to `TF_VAR_*` env vars for consistency, or using a shared tfvars file committed to the repo (with secrets injected via `TF_VAR_*` overrides).

4. **Add `terraform.tfvars` drift detection** — a pre-commit hook or CI step that compares the variables declared in `variables.tf` against the keys in `terraform.tfvars.example` to catch stale assignments early.

---

## Appendix: AWS Resource Inventory (Post-Investigation)

### Resources Present

| Resource | Status |
|---|---|
| `wealth-api-gateway` Lambda | Active, arm64, 2048 MB |
| `wealth-portfolio-service` Lambda | Active, arm64 |
| `wealth-market-data-service` Lambda | Active, arm64 |
| `wealth-insight-service` Lambda | Active, arm64 |
| CloudFront `E3EDXRGMYOSRB1` | Deployed, 2 origins (S3 + Lambda FURL) |
| S3 `vibhanshu-s3-wealthmgmt-demo-bucket` | 58 objects (HTML + JS + CSS + fonts) |
| S3 `vibhanshu-tf-state-2026` | Terraform state |

### Resources Missing (Should Exist)

| Resource | Expected Name | Module |
|---|---|---|
| EventBridge Rule × 4 | `wealth-warm-{api_gateway,portfolio,market_data,insight}` | `warming` |
| EventBridge API Destination × 4 | `wealth-warm-{api_gateway,portfolio,market_data,insight}` | `warming` |
| EventBridge Connection | `wealth-warming-public` | `warming` |
| IAM Role | `wealth-lambda-warming-scheduler` | `warming` |
| SNS Topic | `wealth-lambda-concurrency-alarm` | `warming` |
| SNS Subscription | Email to warming alarm address | `warming` |
| CloudWatch Alarm | `wealth-lambda-concurrent-executions-high` | `warming` |

---

## Reviewer Audit Addendum — Augment Validation (2026-04-25)

**Reviewer:** Augment Agent  
**Status:** Validation complete — report is directionally correct, but several causal claims and fix-order details need correction before implementation.

### Audit Scope

This addendum validates the RCA against:

- Local repository state: Terraform, GitHub Actions workflow, local `terraform.tfvars`, and plan assertion script.
- Live AWS state using `.env.secrets` credentials, without exposing credential values.
- GitHub Actions run history and downloaded Terraform artifacts.
- Live HTTP checks against CloudFront and the Lambda Function URL origin.

### High-Level Verdict

The RCA correctly identifies that:

- Warming resources are currently missing from AWS.
- The Terraform workflow does not pass `enable_warming=true`.
- Local `terraform.tfvars` contains stale `s3_key_api_gateway` after PR #17 removed the variable declaration.
- The frontend static route can respond while API routes return `502`.

However, the report currently overstates the causal chain. The evidence does **not** support the claim that PR #17 destroyed the warming resources, because PR #17's Terraform run failed during plan and skipped apply. The report should also account for a separate CI blocker in `lambda_architecture` validation and a likely incorrect API Gateway warming target.

### Validated Findings

#### 1. Warming Infrastructure Is Missing in AWS

Confirmed in `ap-south-1`:

| Check | Observed Result |
|---|---|
| STS account | `844479804897` |
| EventBridge rules `wealth-warm*` | `[]` |
| EventBridge API destinations `wealth-warm*` | `[]` |
| EventBridge connections `wealth*` | `[]` |
| IAM role `wealth-lambda-warming-scheduler` | `NoSuchEntity` |
| SNS wealth topics | `[]` |
| CloudWatch wealth lambda alarms | `[]` |
| Terraform remote state modules | `root`, `module.compute`, `module.database`, `module.networking`; no `module.warming` |

This validates the inventory section's core conclusion: the expected warming resources are absent.

#### 2. CI Does Not Enable the Warming Module

Validated in `.github/workflows/terraform.yml`:

- No top-level `TF_VAR_enable_warming` exists.
- No `TF_VAR_warming_alarm_email` exists.
- The Terraform plan command does not pass `-var="enable_warming=true"`.

Validated in Terraform:

- `variables.tf` sets `enable_warming` default to `false`.
- `main.tf` gates the module with `count = var.enable_warming ? 1 : 0`.

Therefore, a successful CI apply using the current workflow will not create or preserve warming resources.

#### 3. Local `terraform.tfvars` Has a Stale Variable

Confirmed in `infrastructure/terraform/terraform.tfvars`:

```hcl
s3_key_api_gateway = "api-gateway/api-gateway.jar"
```

Confirmed absent from current Terraform configuration:

- No `variable "s3_key_api_gateway"` in root `variables.tf`.
- No `s3_key_api_gateway` pass-through in `main.tf`.
- No `s3_key_api_gateway` flag in `.github/workflows/terraform.yml`.

This validates the local Terraform issue, but this specific stale variable does not explain the observed CI failure.

#### 4. Live UI/API Behavior Matches the Symptom

Live checks showed:

| Endpoint | Result |
|---|---|
| CloudFront `/` | `200` |
| CloudFront `/api/portfolio/health` | `502` after ~31 seconds |
| CloudFront `/actuator/health` | `403 AccessDenied` from S3 |
| Direct API Lambda origin `/actuator/health` | `200`, fast |

This supports the symptom that static content and API behavior are split, but it also exposes a warming-target issue described below.

### Corrections Required

#### Correction 1: PR #17 Did Not Destroy Warming Infrastructure

The current RCA states that PR #17 triggered a CI apply that destroyed warming resources. GitHub Actions evidence does not support this.

For PR #17 / commit `b1b1535`:

- Workflow run: `24937058038`
- `Validate & Plan`: failed
- `Apply`: skipped
- Failure occurred in Terraform plan, before any apply could run

Observed failure:

```text
Error: Invalid function argument
on variables.tf line 283, in variable "lambda_architecture"
condition = var.lambda_architecture == null || contains(["arm64", "x86_64"], var.lambda_architecture)
```

Therefore, PR #17 could not have destroyed AWS resources through CI apply.

#### Correction 2: The Actual PR #17 CI Failure Was `lambda_architecture` Validation

The report correctly says stale `s3_key_api_gateway` affects local Terraform, not CI. But the actual CI failure after PR #17 was caused by nullable validation on `lambda_architecture`, not by warming or `s3_key_api_gateway`.

Terraform evaluated `contains(["arm64", "x86_64"], null)` and failed during plan. The fix plan should include this as a CI blocker before expecting any CI apply to recreate warming.

#### Correction 3: The API Gateway Warming Target Appears Wrong

Current Terraform target for API Gateway warming is:

```hcl
url = "https://${module.networking.cloudfront_domain_name}/actuator/health"
```

Live validation showed that CloudFront `/actuator/health` does **not** route to the API Gateway Lambda origin. It returns S3 `403 AccessDenied` because only `/api/*` is configured as the API cache behavior.

Therefore, simply adding `enable_warming=true` to CI may recreate warming resources, but the API Gateway warming request is likely to hit the wrong CloudFront origin and fail to warm `wealth-api-gateway`.

The API Gateway warming target should be changed to a live-verified endpoint, likely either:

1. the direct API Gateway Lambda Function URL `/actuator/health`, which returned `200`; or
2. a CloudFront `/api/...` health endpoint that is verified to route to the Lambda origin and return `200`.

#### Correction 4: `.env.secrets` Default Region Is a Footgun

`.env.secrets` set the default AWS region to `us-east-1`, while the Lambda/EventBridge resources under investigation are in `ap-south-1`.

All meaningful Lambda/EventBridge AWS checks in this audit were forced to `ap-south-1`. The report should explicitly note that regional resource checks must use `ap-south-1` even if local env defaults differ.

#### Correction 5: Some Inventory Counts Are Stale

Observed live differences:

- Frontend S3 object count was `106`, not `58`.
- CloudFront `/` returned `200`, but the response body prefix included Next.js `__next_error__`; browser-level validation is still needed before claiming the frontend fully renders correctly.

#### Correction 6: Stale Comment in Root `main.tf` Misnames the Warming Mechanism

`infrastructure/terraform/main.tf:127` still labels the module as:

```hcl
# Warming module — EventBridge Scheduler + SNS alarm
```

The implementation in `modules/warming/main.tf` deliberately uses **EventBridge Rules + API Destinations**, not EventBridge Scheduler. The module's own header (lines 18–21) explains the pivot: Scheduler does not accept `arn:aws:events:...` API Destination ARNs as targets, so Rules are required. The root-level comment was not updated alongside the implementation pivot and will mislead anyone reading top-down.

Action: update the comment to "EventBridge Rules + API Destinations + SNS alarm".

#### Correction 7: `module "networking"` Alias Sources `./modules/cdn`

In `main.tf`, the module declared as `module "networking"` actually has `source = "./modules/cdn"`. The directory `infrastructure/terraform/modules/networking/` exists on disk but is empty and untracked — leftover scaffolding. This is why the warming URL string `module.networking.cloudfront_domain_name` in Correction 3 is sourced from `modules/cdn/outputs.tf` rather than a `networking` module.

Action: either rename the alias to `module "cdn"` (with a state-mv migration) or remove the empty `modules/networking/` directory. Cosmetic but increases reading friction.

#### Correction 8: The Likely Real Destroyer Is an Earlier Dedup-Wave Run, Not PR #17

The original RCA names PR #17 as the agent of destruction. Correction 1 establishes that PR #17's Apply was skipped, so it cannot have destroyed anything. However, three earlier successful Terraform runs in the same dedup wave on 2026-04-25 each ran `terraform apply` with `enable_warming=false` (default):

| Run | Commit | PR | Time (UTC) | Result |
|---|---|---|---|---|
| `24933835083` (#80) | `8bd6946` | terraform fmt | 2026-04-25 15:08 | success |
| `24934469500` (#82) | `6a323cc` | #10 (dedup + Bedrock) | 2026-04-25 15:41 | success |
| `24936255915` (#84) | `9f6936c` | #14 (`lambda_java_runtime` delete) | 2026-04-25 17:15 | success |
| `24936549832` (#86) | `4c8c014` | #16 (`lambda_adapter_layer_arn` delete) | 2026-04-25 17:31 | success |

If the manual local apply documented in `CHANGES_CACHE_WARMING_2026-04-25.md` ran **before** any of these CI applies, that CI apply would have planned a destroy of `module.warming.*` and executed it. The audit's "Not Proven" stance should be sharpened to: "the destroyer is one of runs #80/#82/#84/#86, conditional on the local apply timestamp."

Action: pull the `tfplan`/`apply.txt` artifacts from runs #80, #82, #84, #86 and grep for `module.warming` destroy nodes. Whichever run shows the first destroy is the actual cause; the remainder will be no-ops.

#### Correction 9: Undeclared-Variable Severity in Local Tfvars Is a Warning, Not a Hard Block

The original RCA states that the stale `s3_key_api_gateway` line in `terraform.tfvars` causes `terraform plan` to fail with `Error: Value for undeclared variable "s3_key_api_gateway"`. Terraform's actual default behavior for undeclared variables in a tfvars file is a **warning**, not an error — `plan` and `apply` still proceed. A hard error only occurs in restricted-mode runs (e.g. `TF_INPUT=0` combined with strict CI flags).

Action: re-run `terraform plan` locally with the stale line present and capture the exact diagnostic. If it is a warning, downgrade Issue 3 in the original RCA from "blocker" to "noise". The cleanup is still recommended.

### Revised Causal Assessment

#### Strongly Supported

- Warming resources are currently absent from AWS.
- CI does not set `enable_warming=true`, so successful CI applies will not create or preserve warming.
- Local `terraform.tfvars` is stale and can block local Terraform runs.
- API paths are currently returning `502` through CloudFront.
- Remote state currently has no `module.warming` resources.

#### Not Proven by Available Evidence

- PR #17 specifically destroyed warming resources. Its apply job was skipped.
- The exact destroyer within the dedup PR wave. The strongest candidates are runs #80, #82, #84, and #86 (see Correction 8); confirmation requires inspecting their downloaded `tfplan` / `apply.txt` artifacts for a `module.warming` destroy node.
- The API `502` is solely due to cold starts. Missing warming can contribute, but the consistent `502` should also be investigated through API Gateway/downstream Lambda logs.

### Revised Fix Plan

Recommended order before implementation:

1. **Fix the CI Terraform blocker**
   - Correct nullable validation for `lambda_architecture` so CI plan can run. Either wrap the value (`contains(["arm64", "x86_64"], coalesce(var.lambda_architecture, "arm64"))`) or remove the root-level validation block and rely on the equivalent block already present in `modules/compute/variables.tf`.

2. **Remove stale local variable**
   - Delete `s3_key_api_gateway` from local `infrastructure/terraform/terraform.tfvars` (severity caveat in Correction 9).

3. **Fix the API Gateway warming target**
   - Do not use CloudFront `/actuator/health` — it routes to `frontend-static-s3` (default cache behavior) and returns `403`.
   - Prefer the direct API Gateway Lambda Function URL `/actuator/health` (verified `200`) for parity with the other three targets, unless an `/api/...`-prefixed health path is added to the Spring app and verified end-to-end.

4. **Enable warming in CI**
   - Add `TF_VAR_enable_warming: "true"` to the workflow `env:` block.
   - Add `TF_VAR_warming_alarm_email: "vibhanshu.agarwal@gmail.com"` (or via `secrets.WARMING_ALARM_EMAIL`).
   - Confirm GitHub Actions `AWS_REGION` is `ap-south-1`.

5. **Update stale documentation in code**
   - Fix the `main.tf:127` comment to say "EventBridge Rules + API Destinations + SNS alarm" (Correction 6).
   - Either rename `module "networking"` to `module "cdn"` with a `state mv`, or remove the empty `modules/networking/` directory (Correction 7).

6. **Add prevention checks**
   - Assert warming resources exist in the Terraform plan when `enable_warming=true` (extend `infrastructure/terraform/scripts/assert_plan.py` — currently has zero warming-related assertions).
   - Add a post-apply smoke check for four EventBridge rules and four API destinations.
   - Add no-destroy guardrails for protected warming resources.
   - Add `set -o pipefail` around `terraform apply | tee apply.txt` so Terraform failures are not masked by `tee`.

### Updated Key Takeaway

Replace the current single-gap conclusion with:

> Warming is currently absent, and CI is not configured to enable it. PR #17 did not destroy the warming resources because its apply job was skipped after a `lambda_architecture` validation failure during plan; the actual destroyer is one of the earlier dedup-wave runs (#80, #82, #84, or #86) that successfully applied with `enable_warming=false`. Restoring warming safely requires fixing the `lambda_architecture` CI plan blocker, correcting the API Gateway warming target (which currently points at CloudFront `/actuator/health` and routes to S3 → 403), and only then enabling warming in CI.

### Implementation Readiness

Do **not** implement the original 5-step plan exactly as written. Implement the revised plan above, starting with the Terraform validation blocker and the incorrect API Gateway warming URL, then enable warming in CI and verify live AWS resources.

As of 2026-04-26, warming restoration is parked in [docs/todos/backlog/eventbridge-not-working/README.md](../todos/backlog/eventbridge-not-working/README.md). Application initialization bugs take priority. Do not re-enable `enable_warming` until that backlog item is worked.
