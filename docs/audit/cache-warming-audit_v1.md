# Cache Warming Audit & Optimization Report (v1)

## Executive Summary
This audit was triggered in response to an unexpectedly high consumption of AWS Lambda Free Tier resources. As of the current billing cycle, **345,186.83 GB-seconds** of the 400,000 GB-seconds Free Tier limit have been consumed.

The analysis evaluated the EventBridge warming module, the CI/CD pre-warming logic, and the mathematical relationship between warm pings and JVM cold starts. Based on this review, a set of minimum code changes is recommended to drastically reduce Lambda execution time without compromising the user experience when warming is enabled.

## 1. Usage vs. Quota (The Math of Cold vs. Warm Starts)

The most critical insight is the cost-difference between a warm ping and a cold start.

**Current Configuration (`rate(5 minutes)`):**
- **Invocations:** 12 times/hour × 24 hours × 30 days = 8,640 per target. Across 4 targets, this is **~34,560 invocations/month** (3.4% of the 1M limit).
- **Compute:** A warm start takes ~65ms. Assuming 2GB (2048 MB) of memory per Lambda: `34,560 invocations × 0.065s × 2GB` = **~4,493 GB-seconds/month** (1.1% of the 400,000 GB-s limit).

**Hypothetical Configuration (`rate(30 minutes)` or `rate(60 minutes)`):**
- AWS Lambda typically evicts idle execution environments after 10–15 minutes. By stretching the cron to 30 or 60 minutes, the AWS runtime is **guaranteed to evict the JVM container** between pings.
- This means almost every single warming ping will trigger a full ~10s cold start.
- **Compute for 30-min interval:** 48 times/day × 30 days = 1,440 per target. Across 4 targets = 5,760 invocations/month. `5,760 invocations × 10s × 2GB` = **~115,200 GB-seconds/month**.

**Conclusion:** Paradoxically, moving to a 30- or 60-minute schedule would **increase** compute consumption by roughly **25x** (eating up ~28.8% of the Free Tier limit) because it forces the infrastructure to pay the 10s JVM initialization penalty on every ping.

**Recommendation:** Do not increase the cron to 30/60 minutes. The current `rate(5 minutes)` is mathematically optimal for avoiding JVM eviction while remaining well within the Free Tier.

## 2. CI/CD Pre-warming Logic Optimizations

The extremely high GB-second consumption (345,186 GB-s) is primarily driven by the hourly synthetic tests combined with the broken EventBridge warming.

1. **Hourly Synthetic Tests:** The `.github/workflows/synthetic-monitoring.yml` workflow was running every hour (`0 * * * *`). Because the EventBridge warming was recently broken/disabled (as per the RCA), these hourly E2E tests hit cold Lambdas, forcing massive cold-start penalties and long test durations every single hour.
2. **Redundant Pre-warming:** The "Pre-warm AWS Lambda stack" step in both `synthetic-monitoring.yml` and `ci-verification.yml` executed a 3-pass loop with 10-second sleeps over 4 endpoints. One of those endpoints (`/actuator/health`) was hitting the CloudFront default behavior (S3) and returning a 403, which the script incorrectly treated as a valid warm state.

**Proposed Code Changes:**
- **Reduce Workflow Frequency:** Update the synthetic monitoring schedule from hourly (`0 * * * *`) to daily (`0 0 * * *`). This **cuts E2E traffic and the associated CI cold-starts by 24x**.
- **Optimize Pre-warm Loop:** Refactor the Pre-warm scripts in both workflows to perform a **single pass** instead of 3 passes, saving GitHub Action minutes and redundant Lambda executions.
- **Remove Invalid Target:** Remove the `/actuator/health` path from the CI pre-warm array, as hitting the downstream `/api/*` routes natively routes through the `wealth-api-gateway`, warming it implicitly.

## 3. Verification of `api_gateway` Warming Target

In the RCA document (`RCA_2026-04-26_warming-ui-cicd-failures.md`), the "Reviewer Audit Addendum" claimed that the API Gateway warming target incorrectly pointed to the CloudFront domain.

**Finding:** Cross-referencing this against `infrastructure/terraform/main.tf` (lines 147-150) reveals that the RCA is incorrect on this specific point. The module correctly passes the direct Lambda Function URL:
```hcl
targets = {
  api_gateway = {
    url    = "${module.compute.api_gateway_function_url}actuator/health"
    method = "GET"
  }
}
```
**Conclusion:** The `api_gateway` target is properly configured to bypass CloudFront entirely and avoid the S3 `403 AccessDenied` error. No terraform modifications are needed for this URL binding.
