# Cache Warming Audit & Optimization Report (v3)

**Reviewer:** Augment Agent  
**Date:** 2026-04-29  
**Scope:** Validate `cache-warming-audit_v2.md` against the current repository state. This document records only recommendation-level changes and material clarifications found during validation.

---

## 1. Validation Verdict

`cache-warming-audit_v2.md` is **substantially correct**. Its main conclusion still holds:

> The current free-tier pressure is far more credibly explained by live AWS synthetic monitoring traffic against cold Spring Boot Lambdas than by the EventBridge warming module itself.

However, v2 should be amended in four places:

1. **Provisioned concurrency verification is incomplete.** Terraform can provision concurrency for both `wealth-api-gateway` and `wealth-portfolio-service`; v2 checks only the gateway.
2. **Provisioned concurrency impact is understated.** If accidentally enabled at 2 GB for two functions, it is an order-of-magnitude larger than the 400,000 GB-s free-tier allowance.
3. **Synthetic workflow scope is heavier than v2 implies.** The hourly workflow runs the full `aws-synthetic` Playwright project and global seeding, not just a small live smoke.
4. **The pre-warm pass-1 estimate undercounts chained cold starts.** Gateway-routed health endpoints can pay both the `api-gateway` and downstream Lambda cold-start cost.

These changes do **not** overturn v2's primary recommendation to stop/reduce hourly synthetic monitoring first.

---

## 2. Claims Revalidated as Correct

| v2 finding | Status | Evidence |
|---|---|---|
| `enable_warming` defaults to `false` and is secret-controlled in CI | ✅ Correct | `variables.tf` default is `false`; `.github/workflows/terraform.yml` maps `TF_VAR_enable_warming` to `secrets.TF_VAR_ENABLE_WARMING`. |
| Warming module is gated by `count = var.enable_warming ? 1 : 0` | ✅ Correct | `infrastructure/terraform/main.tf` module `warming`. |
| Default warming cadence is `rate(5 minutes)` | ✅ Correct | Root module uses `coalesce(var.warming_schedule_cron, "rate(5 minutes)")`; module variable default is also `rate(5 minutes)`. |
| Four warming targets use direct Lambda Function URLs, not CloudFront | ✅ Correct | `main.tf` passes `module.compute.*_function_url` for all four targets. |
| CloudFront `/actuator/health` is not an API warm-up path | ✅ Correct | CDN default cache behavior targets S3; only `/api/*` routes to the Lambda origin. |
| Hourly synthetic workflow exists | ✅ Correct | `.github/workflows/synthetic-monitoring.yml` uses `cron: '0 * * * *'`. |
| CI pre-warm loops use 3 passes over 4 paths | ✅ Correct | Both `synthetic-monitoring.yml` and `ci-verification.yml` contain `for pass in 1 2 3` and include `/actuator/health`. |

---

## 3. Material Recommendation Changes

### 3.1 Provisioned concurrency check must include both configured functions

v2 Step 2 recommends:

```text
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
```

That is incomplete. Terraform currently defines provisioned concurrency resources for **two** functions:

- `wealth-api-gateway`
- `wealth-portfolio-service`

Use:

```text
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-portfolio-service --region ap-south-1
```

Optionally check all four wealth functions defensively, but only those two are configured by Terraform today.

### 3.2 Provisioned concurrency impact is much larger than v2's table suggests

v2 says accidental provisioned concurrency could save/explain “up to ~100,000” GB-s. That is too low for the current Terraform shape.

At 2 GB memory:

| Function | Provisioned concurrency | Monthly GB-s equivalent |
|---|---:|---:|
| `wealth-api-gateway` | 1 | `2 GB × 2,592,000 s = 5,184,000 GB-s` |
| `wealth-portfolio-service` | 1 | `2 GB × 2,592,000 s = 5,184,000 GB-s` |
| Total if both enabled | 2 | `10,368,000 GB-s` |

AWS bills provisioned concurrency as a separate meter from normal request duration, so the exact alert line item must be checked in AWS Cost Explorer/Billing. Still, operationally, **confirming provisioned concurrency is off should remain the first read-only AWS check after disabling or reducing the synthetic cron**.

### 3.3 The permanent synthetic fix should prefer “daily smoke-only”

v2 recommends reducing the hourly cron to daily and optionally narrowing the Playwright suite. Validation shows the optional item should be promoted.

The scheduled workflow runs:

```text
npx playwright test --project=aws-synthetic
```

Because `playwright.config.ts` sets the `aws-synthetic` project `testDir` to `./tests/e2e/aws-synthetic`, this runs every spec in that directory, currently including:

- `api-live-smoke.spec.ts`
- `aws-synthetic.spec.ts`
- `login.spec.ts`
- `live-contract.spec.ts`
- `ai-insights.spec.ts`

Additionally, Playwright global setup runs before the project and performs live golden-state seeding unless skipped:

- 3 gateway-routed warm-up polls
- 3 internal seed POSTs
- retry budgets up to 8 attempts with 70-second request timeouts for live AWS

Therefore the recommended structural change should be:

1. **Immediate:** disable the scheduled workflow in the GitHub UI, or change the cron from hourly to daily.
2. **Permanent:** make scheduled synthetic monitoring run only `tests/e2e/aws-synthetic/api-live-smoke.spec.ts` unless a broader canary is explicitly needed.
3. **Keep full `aws-synthetic` project for manual `workflow_dispatch` or release validation**, not hourly/daily background monitoring.

This is more precise than v2's “daily first, smoke-only optional” ordering.

### 3.4 Pre-warm pass-1 cost should be modeled as chained gateway/downstream cold starts

v2's table estimates the first pre-warm pass as:

```text
3 × ~10 s × 2 GB = 60 GB-s
```

That undercounts the first gateway-routed health request. The path is:

```text
CloudFront /api/* → wealth-api-gateway Lambda → downstream Lambda
```

A more realistic first-pass estimate when all functions are cold is:

| Request | Cold components | Approx. GB-s |
|---|---|---:|
| `/api/portfolio/health` | gateway + portfolio | `~20 s × 2 GB = ~40` |
| `/api/market/health` | market-data; gateway likely now warm | `~10 s × 2 GB = ~20` |
| `/api/insights/health` | insight; gateway likely now warm | `~10 s × 2 GB = ~20` |
| Total | — | `~80 GB-s` |

If the gateway is evicted or fails readiness between requests, the value can be higher. This does not change the recommendation: the subsequent Playwright/global-setup traffic remains the larger lever.

---

## 4. Revised Recommended Remediation Order

### Step 1 — Stop or sharply reduce scheduled synthetic traffic

Preferred immediate action remains no-code and reversible:

- Disable the scheduled **Synthetic Monitoring** workflow in GitHub Actions, leaving manual dispatch available.

If a code/config PR is preferred instead:

- Change hourly cron to daily, and
- Run only `tests/e2e/aws-synthetic/api-live-smoke.spec.ts` on the scheduled path.

### Step 2 — Read-only AWS verification

Run these before re-enabling any warming or provisioned-concurrency feature:

```text
aws events list-rules --name-prefix wealth-warm --region ap-south-1
aws events list-api-destinations --name-prefix wealth-warm --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-portfolio-service --region ap-south-1
```

Expected safe state while parked:

- No `wealth-warm*` EventBridge rules/API destinations, unless warming was deliberately re-enabled.
- No provisioned concurrency configs for gateway or portfolio, unless intentionally enabled after quota/cost review.

### Step 3 — Keep v2's small workflow cleanups, but treat them as secondary

These remain valid but are not the primary bill-reduction lever:

1. Collapse CI/live pre-warm from 3 passes to 1 pass.
2. Remove `/actuator/health` from CloudFront-based pre-warm arrays with the corrected rationale: it routes to S3/default behavior, not Lambda.
3. Keep the EventBridge warming cadence at 5 minutes if warming is re-enabled; do not stretch it to 30/60 minutes expecting savings.

### Step 4 — Reconsider EventBridge warming only after app init is stable

This remains unchanged from v2. The backlog still marks warming as parked, and the current Terraform defaults keep the warming module disabled unless CI secrets opt in.

---

## 5. Updated Risk/Impact Table

| Action | Risk | Expected impact |
|---|---|---|
| Disable scheduled synthetic workflow | Very low | Immediate halt to the largest plausible live Lambda traffic source. |
| Scheduled synthetic: hourly → daily | Low | Up to 24× reduction of scheduled synthetic traffic. |
| Scheduled synthetic: full project → smoke-only | Low/medium | Reduces live API/seeding/UI/Bedrock calls per run; recommended for background monitoring. |
| Verify provisioned concurrency for gateway + portfolio | None | Confirms no accidental 24/7 concurrency meter is active. |
| Collapse 3-pass pre-warm to 1 pass | Low | Small but worthwhile reduction in repeated health calls. |
| Remove `/actuator/health` from CloudFront pre-warm arrays | Very low | Saves CI time/noise; no direct Lambda GB-s savings. |
| Re-enable EventBridge warming at 5 minutes | Low once app init is stable | Adds bounded warm-hit traffic; can reduce cold-start failures for real/synthetic traffic. |

---

## 6. Bottom Line

v2's strategic recommendation is valid: **do not tune EventBridge cadence first; reduce scheduled live synthetic traffic first.**

The changed recommendations are:

1. Verify provisioned concurrency for both Terraform-configured functions, not just `wealth-api-gateway`.
2. Treat provisioned concurrency as a potentially very large separate cost/usage meter if enabled.
3. Promote scheduled synthetic narrowing to `api-live-smoke.spec.ts` from optional to recommended.
4. Use a chained cold-start model for gateway-routed pre-warm estimates.

No application, Terraform, or workflow code changes were made as part of this validation.