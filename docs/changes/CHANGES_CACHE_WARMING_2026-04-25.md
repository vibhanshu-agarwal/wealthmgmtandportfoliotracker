# Cache Warming Enhancement — 2026-04-25

**Previous revision:** [CHANGES_PHASE3_SUMMARY_2026-04-21.md](./CHANGES_PHASE3_SUMMARY_2026-04-21.md) — Production security hardening, Redis TLS, and E2E test orchestration.

---

## Summary

This document covers the two-phase Lambda cold-start mitigation ("stopgap") plan applied on 2026-04-25. The goal was to eliminate multi-second cold starts observed in production (up to 9.8 s init duration on arm64, down from ~52 s on x86 with a blocking Redis health check) while staying within the $10–$15/month budget constraint.

- **Phase 1 — ARM64 Flip**: All 4 Lambda functions migrated to Graviton2 (`arm64`) for a 5–15% cold-start reduction and ~20% compute cost reduction.
- **Phase 2 — Warming Infrastructure**: EventBridge Rules fire every 5 minutes to hit `/actuator/health` on each function, keeping execution environments alive.

---

## 1. Phase 1 — ARM64 Flip (Graviton2)

### 1.1 Architecture Change

`lambda_architecture` set to `"arm64"` in `terraform.tfvars`. All four Lambda functions now declare `architectures = ["arm64"]` via the variable threaded through `modules/compute/variables.tf` and `modules/compute/main.tf`:

- `wealth-api-gateway`
- `wealth-portfolio-service`
- `wealth-market-data-service`
- `wealth-insight-service`

**Baseline after flip (24 h CloudWatch Insights):**

| Function | Invocations | Cold Starts | Init Duration (avg) | Init Duration (max) |
|---|---|---|---|---|
| api-gateway | 138 | 28 | 8,926 ms | 9,825 ms |
| portfolio | 138 | 27 | 9,813 ms | 9,830 ms |
| market-data | 0 | 0 | — | — |
| insight | 0 | 0 | — | — |

### 1.2 Redis Health-Check Fix

**Root cause of 52-second stall:** The Spring Boot `HealthIndicator` for Redis was blocking the Lambda Web Adapter readiness check until the Upstash TLS handshake timed out on first init. This pinned the cold start at the full LWA readiness timeout.

**Fix:** Disabled the Redis health contribution in `api-gateway/src/main/resources/application-prod.yml`:

```yaml
management:
  health:
    redis:
      enabled: false
```

This reduced the api-gateway cold start from ~52 s to the JVM baseline of ~9.8 s.

---

## 2. Phase 2 — Warming Infrastructure

### 2.1 New Module: `infrastructure/terraform/modules/warming/`

A new Terraform module provisions the full warming stack. It is gated by `enable_warming` in `terraform.tfvars` and is created as `count = var.enable_warming ? 1 : 0` in the root module.

**Resources created:**

| Resource | Name | Purpose |
|---|---|---|
| `aws_cloudwatch_event_connection` | `wealth-warming-public` | Required credential wrapper for API Destinations (dummy API key; header ignored by Spring) |
| `aws_cloudwatch_event_api_destination` | `wealth-warm-{service}` × 4 | HTTPS GET destination bound to each service's `/actuator/health` URL |
| `aws_cloudwatch_event_rule` | `wealth-warm-{service}` × 4 | `rate(5 minutes)` scheduled trigger per service |
| `aws_cloudwatch_event_target` | one per rule | Binds each rule to its API Destination; fire-and-forget retry policy |
| `aws_iam_role` | `wealth-lambda-warming-scheduler` | Allows `events.amazonaws.com` to call `events:InvokeApiDestination` |
| `aws_sns_topic` | `wealth-lambda-concurrency-alarm` | Alarm notification channel |
| `aws_sns_topic_subscription` | email to `warming_alarm_email` | Email delivery of alarm events |
| `aws_cloudwatch_metric_alarm` | `wealth-lambda-concurrent-executions-high` | Fires when account-wide `ConcurrentExecutions ≥ 8` (buffer below hard limit of 10) |

### 2.2 Warming Topology

| Service | Target URL | Reason |
|---|---|---|
| `api_gateway` | `https://d1t9eh6t95r2m3.cloudfront.net/actuator/health` | Must go through CloudFront to satisfy `CloudFrontOriginVerifyFilter` |
| `portfolio` | Direct Lambda Function URL | No origin-verify filter on FURL |
| `market_data` | Direct Lambda Function URL | No gateway-routable health endpoint |
| `insight` | Direct Lambda Function URL | No gateway-routable health endpoint |

### 2.3 Infrastructure Pivot: Scheduler → Rules

**Problem encountered:** The initial design used `aws_scheduler_schedule` (EventBridge Scheduler). During `terraform apply`, AWS returned:

```
ValidationException: Provided Arn is not in correct format
```

EventBridge Scheduler's target validation rejects `arn:aws:events:...:api-destination/...` ARNs entirely — it does not support API Destinations as a target type.

**Resolution:** Replaced the entire Scheduler approach with EventBridge Rules (`aws_cloudwatch_event_rule` + `aws_cloudwatch_event_target`), which natively support API Destinations. This also required:

- Removing `aws_scheduler_schedule_group` (destroyed on apply)
- Updating IAM role trust policy: `scheduler.amazonaws.com` → `events.amazonaws.com`
- Updating `outputs.tf`: removed `schedule_group_name`/`schedule_arns`, added `rule_arns`

### 2.4 Files Modified

| File | Change |
|---|---|
| `infrastructure/terraform/modules/warming/main.tf` | Complete rewrite: Scheduler resources removed, EventBridge Rules + Targets added; IAM trust updated to `events.amazonaws.com` |
| `infrastructure/terraform/modules/warming/outputs.tf` | `schedule_group_name`/`schedule_arns` → `rule_arns` |
| `infrastructure/terraform/terraform.tfvars` | `lambda_architecture = "arm64"`, `enable_warming = true`, `warming_alarm_email` set |

### 2.5 Apply Result

```
Apply complete! Resources: 8 added, 2 changed, 1 destroyed.
```

Post-apply verification via `aws events list-rules --name-prefix wealth-warm`:

```
wealth-warm-api_gateway   ENABLED   rate(5 minutes)
wealth-warm-insight       ENABLED   rate(5 minutes)
wealth-warm-market_data   ENABLED   rate(5 minutes)
wealth-warm-portfolio     ENABLED   rate(5 minutes)
```

---

## 3. Cost Impact

| Component | Monthly invocations | Cost |
|---|---|---|
| EventBridge (4 rules × 12/hr × 24 × 30) | 34,560 | Free (first 14M/month free) |
| Lambda warm-hit compute | ~34,560 × ~50 ms | Free (within Free Tier) |
| SNS | < 1,000 notifications/month | Free |
| **Total warming overhead** | | **$0** |

---

## 4. Rollback

- **Phase 2:** Set `enable_warming = false` in `terraform.tfvars`, run `terraform apply`. Rules are deleted within ~1 minute. Zero data loss.
- **Phase 1:** Revert `lambda_architecture = "x86_64"` and redeploy the x86 container image. Total rollback time ≤ 10 min.

---

## 5. Next Verification Steps

1. **Confirm SNS subscription** — click the "Confirm subscription" link in the AWS email sent to `warming_alarm_email` immediately after apply.
2. **Wait ~5 minutes** — then check CloudWatch Logs for each Lambda function to confirm `GET /actuator/health` hits are arriving every 5 minutes.
3. **After 24 h** — recheck `InitDuration` via CloudWatch Logs Insights. Cold starts should drop significantly or disappear for `api-gateway` and `portfolio`.
