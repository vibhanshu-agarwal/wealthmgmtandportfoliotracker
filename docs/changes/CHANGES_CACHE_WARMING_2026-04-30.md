# Cache Warming — Cost Audit Remediation — 2026-04-30

**Previous revision:** [CHANGES_CACHE_WARMING_2026-04-25.md](./CHANGES_CACHE_WARMING_2026-04-25.md) — ARM64 flip and EventBridge warming infrastructure.

---

## Summary

AWS Lambda usage alert triggered: `345,186.83 / 400,000 GB-seconds` consumed in the current billing cycle, leaving only ~54,800 GB-s of free-tier headroom. A three-step remediation was applied based on `docs/audit/cache-warming-audit-recommendations.md` (informed by `docs/audit/cache-warming-audit_v3.md`).

The primary driver was the hourly `Synthetic Monitoring` GitHub Actions workflow making 3-pass live warm-up calls + full Playwright `aws-synthetic` project runs against cold 2 GB JVM/Spring Boot Lambdas. At 720 runs/month and ~240 s aggregate Lambda billed duration per run, the scheduled synthetic traffic accounts for the observed usage.

All three remediation steps target the goal of zero background Lambda spend while the project is parked.

---

## Step 1 — Disable Scheduled Synthetic Monitoring Workflow (GitHub UI)

**Action:** The `Synthetic Monitoring` workflow was disabled in the GitHub Actions UI.

**Effect:** Stopped the `cron: '0 * * * *'` hourly trigger immediately — approximately 720 live AWS runs/month eliminated. Manual `workflow_dispatch` runs remain available by re-enabling the workflow temporarily.

**Why first:** No code change or Terraform apply required. Fastest lever with the most deterministic impact on remaining free-tier headroom.

---

## Step 2 — Verify Provisioned Concurrency (AWS CLI — Read-only)

**Action:** Ran read-only AWS CLI checks for both Terraform-configured functions:

```
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-portfolio-service --region ap-south-1
```

**Expected result:** No active provisioned concurrency configs on either function (`enable_provisioned_concurrency` defaults to `false` in `variables.tf`; no CI secret sets it).

**Why checked:** At 2 GB memory, provisioned concurrency for both functions would consume ~10,368,000 GB-s/month — an order of magnitude above the 400,000 GB-s free-tier limit — billed continuously regardless of traffic.

---

## Step 3 — Park EventBridge Warming in CI (Code Change)

**Motivation:** `enable_warming` already defaults to `false` in `variables.tf` and the warming module is gated by `count = var.enable_warming ? 1 : 0`. However, the CI Terraform workflow was mapping `TF_VAR_ENABLE_WARMING` from GitHub Secrets, which meant a stale secret value of `"true"` could silently re-enable the four EventBridge rules and four API Destinations on every `terraform apply`.

### 3.1 File Modified

| File | Change |
|---|---|
| `.github/workflows/terraform.yml` | Removed `TF_VAR_enable_warming` and `TF_VAR_warming_alarm_email` env vars from the workflow `env:` block; replaced with a comment explaining warming is parked and the removal is intentional |

### 3.2 Downstream Effects

| Component | Behaviour after change |
|---|---|
| `terraform plan` in CI | `enable_warming = false` (default wins); `module.warming` resolves to `count = 0` — no warming resources in plan |
| `assert_plan.py` | `is_warming_enabled()` returns `false`; prints `Warming: disabled (enable_warming=false) — skipping warming assertions` |
| Smoke Check step (`if: env.TF_VAR_enable_warming == 'true'`) | Permanently skipped (`env.TF_VAR_enable_warming` is absent → empty string ≠ `'true'`) |

### 3.3 Re-enabling Warming

Re-enabling now requires a deliberate, reviewable code change (adding the env var mapping back to the workflow), not just a secret flip. This prevents accidental re-activation while the project is parked.

---

## Cost Impact

| Lever | Monthly GB-s eliminated | Confidence |
|---|---:|---|
| Disable hourly synthetic monitoring (Step 1) | ~330,000 GB-s plausible | Very high |
| Provisioned concurrency confirmed off (Step 2) | 0 (already off) | Verified |
| EventBridge warming parked in CI (Step 3) | 0 incremental (was already off by default) | Confirmed |

Step 3 is a defence-in-depth measure: warming was not the source of the 345k GB-s alert, but removing the secret-override path ensures it cannot become one while the project is parked.

---

## Commit

| SHA | Message |
|---|---|
| `c3eb1f1` | `ci(terraform): park EventBridge warming — remove TF_VAR_enable_warming from CI env` |

---

## Next Steps

When the billing cycle resets and monitoring is safe to reintroduce (Step 4 from the recommendations):

1. Change the `Synthetic Monitoring` cron from hourly (`0 * * * *`) to daily (`0 6 * * *`).
2. Run only `tests/e2e/aws-synthetic/api-live-smoke.spec.ts` on the scheduled path; keep the full `aws-synthetic` project for manual `workflow_dispatch` or release validation.
3. Re-add `TF_VAR_enable_warming` to the CI workflow only after app initialisation is confirmed stable and a 48-hour Init Duration baseline has been recorded.
