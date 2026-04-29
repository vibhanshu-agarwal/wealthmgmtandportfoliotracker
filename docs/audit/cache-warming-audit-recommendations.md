# Cache Warming Audit Recommendations

**Date:** 2026-04-29  
**Input:** AWS usage alert `345,186.83 / 400,000 Lambda-GB-Seconds` and `docs/audit/cache-warming-audit_v3.md`  
**Purpose:** Identify the fastest, lowest-risk lever to maintain a zero-cost posture and halt further Lambda GB-second burn.

---

## 1. Short Answer

Yes — **if provisioned concurrency is not active**, the scheduled hourly `Synthetic Monitoring` workflow is the most effective repo-visible lever to stop Lambda GB-second consumption.

The safest immediate action is:

1. **Disable the `Synthetic Monitoring` workflow in the GitHub Actions UI now** to stop hourly live AWS traffic.
2. **Verify provisioned concurrency is not active** for `wealth-api-gateway` and `wealth-portfolio-service`.
3. Keep EventBridge warming disabled while the project is in “parked” mode.
4. Later, re-enable monitoring as **daily + smoke-only**, not hourly full-suite.

---

## 2. Is the Hourly Synthetic Cron the Primary Driver?

### Repo Evidence Says: Very Likely Yes

`.github/workflows/synthetic-monitoring.yml` currently has:

- An hourly schedule: `cron: '0 * * * *'`
- A 3-pass live pre-warm step against production
- A full Playwright run against the live AWS site: `npx playwright test --project=aws-synthetic`

Because the Lambdas are 2 GB JVM/Spring Boot Lambdas, and because warming is parked/off unless enabled by secret/state, a one-hour gap is long enough for Lambda execution environments to be evicted. Each hourly run can therefore trigger cold starts across:

- `wealth-api-gateway`
- `wealth-portfolio-service`
- `wealth-market-data-service`
- `wealth-insight-service`

At 2 GB memory, even moderate cold-start and test duration becomes expensive in Lambda GB-seconds.

### Why It Can Explain the Alert

The alert is:

```text
345,186.83 / 400,000 Lambda-GB-Seconds
```

Hourly synthetic monitoring runs about:

```text
24 × 30 = 720 runs/month
```

To consume about 345,000 GB-s/month, the workflow would need to average roughly:

```text
345,186 / 720 ≈ 479 GB-s per run
```

At 2 GB memory, that is equivalent to about:

```text
479 / 2 ≈ 240 seconds of aggregate Lambda billed duration per run
```

That is plausible for the current workflow because it includes:

- Cold gateway and downstream calls
- Multiple health endpoints
- Global setup seeding
- API calls
- UI/browser flows
- Potential Bedrock/insights paths
- Retries/timeouts around cold starts

Therefore, the hourly synthetic cron is the most likely primary controllable driver **unless provisioned concurrency is active**.

---

## 3. Frequency Reduction vs. Test Scope Narrowing

### Hourly → Daily

This is the more deterministic savings lever.

| Change | Effect |
|---|---:|
| Hourly | ~720 runs/month |
| Daily | ~30 runs/month |
| Reduction | ~24× fewer live AWS synthetic runs |

If the current scheduled workflow is responsible for most of the 345k GB-s, changing from hourly to daily could reduce that scheduled component by roughly **95.8%**.

Example:

| Scenario | Approx monthly scheduled usage |
|---|---:|
| Current hourly full synthetic | ~345k GB-s plausible |
| Daily same full synthetic | ~14k–15k GB-s plausible |

So frequency reduction is the biggest single code/config change.

### Full `aws-synthetic` Project → `api-live-smoke.spec.ts`

This reduces per-run cost, but the savings are less deterministic because Playwright `globalSetup` still runs unless the workflow/config also avoids seeding.

The current scheduled command runs the whole project:

```text
npx playwright test --project=aws-synthetic
```

Per `cache-warming-audit_v3.md`, that project includes multiple files such as:

- `api-live-smoke.spec.ts`
- `aws-synthetic.spec.ts`
- `login.spec.ts`
- `live-contract.spec.ts`
- `ai-insights.spec.ts`

Narrowing to `api-live-smoke.spec.ts` would reduce browser/UI/Bedrock-heavy checks, but if global setup still performs live seeding, the fixed per-run Lambda cost remains significant.

### Best Permanent Combination

For long-term zero-cost posture:

1. **Daily instead of hourly**
2. **Smoke-only instead of full project**
3. Ideally avoid live golden-state seeding for scheduled smoke runs unless required

Priority order:

| Lever | Immediate impact | Confidence |
|---|---:|---|
| Disable scheduled synthetic workflow | Highest | Very high |
| Hourly → daily | Very high, ~24× | Very high |
| Full project → smoke-only | Medium/high | Depends on global setup/seeding |
| 3-pass pre-warm → 1-pass | Low/medium | Secondary |
| Remove `/actuator/health` from CloudFront pre-warm | Low for Lambda GB-s | Mostly removes noise/CI time |

---

## 4. Are Provisioned Concurrency Settings Independently Contributing?

### From Code Alone: Not Unless Enabled Remotely

Terraform defines provisioned concurrency for two functions:

- `wealth-api-gateway`
- `wealth-portfolio-service`

Both are gated behind:

```text
var.enable_provisioned_concurrency
```

That variable defaults to `false`.

The current Terraform GitHub workflow wires warming secrets, but does not visibly set `TF_VAR_enable_provisioned_concurrency`.

So from repository state alone:

> Provisioned concurrency should not be active by default.

### But This Must Be Verified in AWS

Provisioned concurrency is independent of synthetic monitoring traffic. If active, it bills continuously even when there are no requests.

At the current 2 GB memory size, if Terraform’s two provisioned-concurrency resources were active:

| Function | PC | Monthly GB-s equivalent |
|---|---:|---:|
| `wealth-api-gateway` | 1 | ~5,184,000 GB-s |
| `wealth-portfolio-service` | 1 | ~5,184,000 GB-s |
| Total | 2 | ~10,368,000 GB-s |

AWS may show provisioned concurrency under a separate billing/usage meter from normal request Lambda GB-seconds. But for a zero-cost goal, **any active provisioned concurrency is a hard blocker**.

Run these read-only checks:

```text
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-portfolio-service --region ap-south-1
```

Expected safe result: no provisioned concurrency configs.

---

## 5. Recommended Immediate Steps to Halt Billing Bleed

### Step 1 — Disable Scheduled Synthetic Monitoring in GitHub UI

This is the fastest no-code action.

Go to:

```text
GitHub → Actions → Synthetic Monitoring → ⋯ → Disable workflow
```

This should stop the hourly scheduled runs immediately.

Caveat: disabling a workflow in the GitHub UI generally disables the workflow as a whole, not only the cron trigger. If manual runs are needed later, re-enable it temporarily or make a follow-up workflow change to remove/alter only the schedule.

### Why This First?

Because it avoids:

- Terraform apply risk
- Code changes
- Waiting for a PR
- More hourly cold-start cycles

Given only about `400,000 - 345,186.83 = 54,813.17 GB-s` remain before the free-tier threshold, stopping the hourly job is the best immediate move.

### Step 2 — Verify No Provisioned Concurrency Is Active

Run:

```text
aws lambda list-provisioned-concurrency-configs --function-name wealth-api-gateway --region ap-south-1
aws lambda list-provisioned-concurrency-configs --function-name wealth-portfolio-service --region ap-south-1
```

If either returns active provisioned concurrency, treat that as urgent. Disable through Terraform by ensuring `enable_provisioned_concurrency=false` and applying only after confirming the plan destroys those resources.

### Step 3 — Verify Warming Is Still Parked/Off

Run:

```text
aws events list-rules --name-prefix wealth-warm --region ap-south-1
aws events list-api-destinations --name-prefix wealth-warm --region ap-south-1
```

Expected parked state:

- No `wealth-warm*` rules
- No `wealth-warm*` API destinations

EventBridge warming at 5 minutes is not the likely source of the 345k GB-s if it is working normally, but for a strict zero-background-cost posture, it should remain off until app initialization is stable.

### Step 4 — Later Follow-Up: Reintroduce Monitoring Safely

After the current billing cycle resets or alert risk is gone, reintroduce monitoring with a smaller blast radius:

1. Change scheduled synthetic from hourly to daily.
2. Run only `api-live-smoke.spec.ts` on the scheduled path.
3. Keep full `aws-synthetic` project for manual runs or release validation.
4. Consider skipping golden-state seeding for scheduled smoke if the smoke test does not require a fresh reset.

---

## 6. Final Recommendation

For maintaining a zero-cost state:

1. **Immediately disable the scheduled `Synthetic Monitoring` workflow in the GitHub UI.**
2. **Verify provisioned concurrency is absent for both gateway and portfolio.**
3. **Keep EventBridge warming disabled while parked.**
4. **Do not spend time first on small workflow optimizations like removing `/actuator/health` or reducing pre-warm passes.** Those are valid cleanups, but they are not the main bleed.
5. **When re-enabling monitoring, use daily smoke-only, not hourly full-suite.**

The only condition that would outrank disabling synthetic monitoring is if AWS shows active provisioned concurrency; in that case, disable provisioned concurrency immediately because it bills independently of traffic.