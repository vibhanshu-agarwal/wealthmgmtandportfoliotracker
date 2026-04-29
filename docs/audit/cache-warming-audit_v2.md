# Cache Warming Audit & Optimization Report (v2)

**Reviewer:** Augment Agent
**Date:** 2026-04-29
**Scope:** Validate `cache-warming-audit_v1.md` against repository state, the 2026-04-26 RCA, and the AWS usage threshold alert (`345,186.83 / 400,000 Lambda-GB-Second`). Recommend a minimal-risk remediation.

---

## 1. Validation Verdict

v1 is **directionally correct on three of its four claims** but contains framing errors that make the recommendations misleading on their own. The current free-tier burn is **NOT** primarily driven by EventBridge warming — it is driven by the **hourly Synthetic Monitoring workflow exercising cold Lambdas through Playwright on the live production deployment**. The single most effective change is reducing that schedule.

| v1 claim | Status | Notes |
|---|---|---|
| Warm ping math (`34,560 inv/mo`, `~4,493 GB-s/mo` at 5-min cadence, 2 GB) | ✅ Correct | Matches `locals.tf` (`*_memory_mb = 2048`) and `main.tf` (`rate(5 minutes)`). |
| Moving to `rate(30 min)` / `rate(60 min)` increases cost ~25× | ⚠️ Correct **only if warming is active** | Premise unverified — see §2. |
| Hourly synthetics + broken warming → 345k GB-s | ✅ Direction correct, attribution incomplete | The bigger driver is the Playwright test traffic per run, not the 4-endpoint pre-warm. See §3. |
| API Gateway target is the direct Function URL, not CloudFront (RCA Correction 3 stale) | ✅ Correct | Verified in `infrastructure/terraform/main.tf` lines 147–150 and the comment at line 137. |

---

## 2. Issues With v1's Framing

### 2.1 v1 silently assumes `enable_warming = true`

`infrastructure/terraform/variables.tf:341–343` declares `enable_warming` with **`default = false`**. `.github/workflows/terraform.yml:59` wires it to repository secret `TF_VAR_ENABLE_WARMING`. The state of warming in production is therefore secret-controlled and cannot be asserted from code alone.

The 2026-04-26 RCA explicitly states warming is **parked** ("Status: Deferred — warming work parked"). If the secret is unset/false, no EventBridge Rules are firing, no API Destinations exist, and the `~4,493 GB-s/mo` baseline in v1 §1 contributes **zero** to the 345,186 GB-s figure. Conversely, every hourly Synthetic Monitoring run lands on **fully cold** Lambdas because AWS evicts idle environments after ~10–15 minutes.

**Action:** before applying any of v1's recommendations, verify with one CLI call (no code change):

```
aws events list-rules --name-prefix wealth-warm --region ap-south-1
aws events list-api-destinations --name-prefix wealth-warm --region ap-south-1
```

If both return `[]`, warming is OFF and v1 §1's "5-min cron is optimal" argument is moot until warming is re-enabled.

### 2.2 Removing `/actuator/health` from the CI pre-warm does not save Lambda GB-s

v1 §2 recommends removing `/actuator/health` from the `PATHS=(...)` array in `.github/workflows/synthetic-monitoring.yml` and `.github/workflows/ci-verification.yml`. That URL goes to **CloudFront → default cache behavior → S3 → 403 AccessDenied** (per the `main.tf` comment at lines 137–141 and RCA Correction 3). It never reaches a Lambda. Removing it saves ~5–10 seconds of GitHub Actions runtime; **it does not reduce the Lambda-GB-Second number that triggered the alert.**

### 2.3 3-pass → 1-pass pre-warm is a small win, not a primary lever

In each pre-warm run, pass 1 incurs the 4 cold-start invocations; passes 2–3 hit warm Lambdas at ~65 ms × 2 GB ≈ `0.13 GB-s` per call. Switching to a single pass saves roughly **`12 × 0.13 ≈ 1.5 GB-s` per CI run** — negligible compared to the test traffic that follows. v1 frames this as a primary optimization; it is at best secondary.

### 2.4 Cold-start cost is understated for chained calls

v1 uses `~10 s × 2 GB = 20 GB-s` per cold call. For the gateway-routed paths (`/api/portfolio/health`, etc.), the request traverses **CloudFront → wealth-api-gateway → downstream Lambda**, chaining two cold starts. The 2026-04-26 RCA recorded a combined chain of ~20 s minimum. Realistic per-chained-call cost is therefore **`~40 GB-s`** when both Lambdas are cold, which they are at 1-hour idle.

### 2.5 Free-Tier consumption attribution

The 345,186 GB-s of consumption needs a credible breakdown before tuning. A defensible model with warming OFF:

| Source | Per-event cost | Events / month | Subtotal (GB-s) |
|---|---|---|---|
| Synthetic Monitoring pre-warm pass 1 (3 cold Lambdas hit; `/actuator/health` is S3) | 3 × ~10 s × 2 GB = 60 | 720 (hourly × 30 d) | 43,200 |
| Synthetic Monitoring Playwright suite against live AWS | ~10–30 s of Lambda time per run × 2 GB | 720 | 144,000 – 432,000 |
| `ci-verification.yml` pre-warm + synthetic on push to `main` | similar order, but bounded by merge frequency | ~30–60 | 6,000 – 30,000 |
| Real user traffic + warming (if active) | small | — | < 10,000 |

The Synthetic Monitoring workflow (hourly cron) is the dominant contributor in any plausible split — typically **>70 %** of the bill. v1 identifies it but underweights it relative to the pre-warm tweaks.

---

## 3. Recommended Remediation (Minimum Risk)

Order is from lowest blast radius to highest. **Stop after the first step solves the alert.**

### Step 1 — Immediate stop-the-bleeding (no code change, reversible in 1 minute)

Disable the Synthetic Monitoring cron from the **GitHub UI** (Actions → Synthetic Monitoring → ⋯ → *Disable workflow*). This:

- Halts further hourly Lambda consumption immediately.
- Leaves `workflow_dispatch` available so on-demand runs are still possible.
- Requires no commit, no PR, no Terraform apply.

Free-Tier headroom: `400,000 − 345,186 = 54,813 GB-s` remaining. At the current implied burn (~11,500 GB-s/day if 30 d into the cycle), the workflow being disabled today preserves ~5 days of headroom, comfortably crossing the next billing reset.

### Step 2 — Verify warming state and provisioned concurrency (read-only)

Run the AWS CLI checks listed in §2.1, plus:

```
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
```

If `enable_provisioned_concurrency` (root `variables.tf`) is true, **provisioned concurrency bills 24/7** at full memory — that alone could explain a multi-hundred-thousand GB-s bill. Confirm it is `false` in remote state.

### Step 3 — When the next billing cycle resets, apply v1's structural fixes (gated)

Adopt v1's recommendations in this order, each as its own small PR:

1. **`synthetic-monitoring.yml`**: change `cron: '0 * * * *'` to `cron: '0 0 * * *'` (daily). 24× reduction. ✅ from v1.
2. **`synthetic-monitoring.yml` and `ci-verification.yml`**: collapse the `for pass in 1 2 3` loop to a single pass. ✅ from v1, but expect only ~1.5 GB-s savings per run.
3. **`PATHS` array**: remove `/actuator/health` *only* with the corrected rationale — "CloudFront default cache behavior routes it to S3 (403); it never reaches a Lambda, so it cannot serve as a warm-up signal." Do **not** claim a GB-s saving from this change.
4. Optional: scope the Playwright suite used by the synthetic workflow to a tighter smoke (`api-live-smoke.spec.ts` only) instead of the full `aws-synthetic` project, so per-run Lambda time drops by an order of magnitude even on daily cadence.

### Step 4 — Only after Steps 1–3 are stable: reconsider EventBridge warming

Re-enabling warming (`TF_VAR_ENABLE_WARMING=true`) costs ~4,500 GB-s/month — **1.1 % of the free tier**, well-bounded — and prevents the synthetic from paying full cold-start cost on every run. v1's §1 conclusion that 5-min cadence is optimal *vs. 30/60 min* is sound; the open question is whether warming should be on at all given the project status. Defer this decision to the owner of `docs/todos/backlog/eventbridge-not-working/README.md`.

---

## 4. What v1 Got Right and Should Be Kept Verbatim

- The cold-vs-warm math in §1 (within the 5/30/60-min comparison).
- The recommendation to drop the synthetic cron from hourly to daily.
- The correction of the RCA's stale claim about the API Gateway warming target — verified accurate against `main.tf` lines 147–150.
- The framing that 30- and 60-minute warming intervals are paradoxically more expensive than 5-minute warming.

---

## 5. Summary Table — Risk vs. Impact

| Action | Risk | Lambda GB-s saved / mo | Source |
|---|---|---|---|
| Disable Synthetic Monitoring workflow (UI toggle) | None — fully reversible | ~150,000 – 430,000 | This report (Step 1) |
| Hourly → daily cron | Low | ~24× reduction of synthetic share | v1 §2 + this report |
| 3-pass → 1-pass pre-warm | Low | ~1,000 – 2,000 | v1 §2 (impact recalculated here) |
| Drop `/actuator/health` from PATHS | None | 0 (CI seconds only) | This report (§2.2) |
| Verify `enable_provisioned_concurrency = false` | None (read-only) | Up to ~100,000 if accidentally on | This report (§3 Step 2) |
| Re-enable EventBridge warming at `rate(5 min)` | Low (bounded by SNS alarm) | Adds ~4,500; saves cold-start cost on synthetics | v1 §1 |

The alert can be cleared by Step 1 alone. Steps 2–4 are structural follow-ups for the next billing cycle.
