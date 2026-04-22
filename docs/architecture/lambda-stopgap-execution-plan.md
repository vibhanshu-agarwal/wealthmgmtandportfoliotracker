# Lambda Stopgap — Execution Plan

**Date:** 2026-04-22 (revised — §12 decisions resolved, §2.9 pre-flight outcomes recorded, §7.1.1 corrected per P6, P2 + P8 verified via AWS CLI, P7 verified via `gh`, **P4 formal verified via Gradle + JAR inspection — all 8 pre-flights clear**)
**Companion to:** [`lambda-vs-lightsail-analysis.md`](./lambda-vs-lightsail-analysis.md) (§2.1.1, §2.2.1)
**Decision captured:** Stay on Lambda. Execute ARM64 architecture flip + EventBridge scheduled warming (Option 2, all 4 functions) + Playwright E2E hardening.
**Budget envelope:** ~$2/month steady-state (well under the $10 preference).
**Scope boundary:** No Lightsail work, no Provisioned Concurrency, no code refactor of controllers or domain logic.

---

## 1. Objectives and Success Criteria

| # | Objective | Measurable Success Criterion |
|---|-----------|------------------------------|
| 1 | Eliminate idle-instance cold starts for the steady-state CloudFront traffic | First user request after ≥ 10 min of idle returns in < 3 s end-to-end (vs. ~25–40 s today) |
| 2 | Reduce compute cost envelope by 20% to create headroom for future PC | Lambda duration line item falls by 15–20% in the AWS Cost Explorer 30-day view |
| 3 | Stabilise Playwright synthetic monitoring runs | `synthetic-monitoring.yml` green rate over 14 days ≥ 95% (vs. current baseline) |
| 4 | Zero regressions in existing CI pipelines | `ci-verification.yml` and `frontend-e2e-integration.yml` pass on `main` |
| 5 | No code changes to controllers, domain, or Spring config | Only changes are in Dockerfiles (1 line), Terraform, GitHub Actions, Playwright config |

Non-goals (explicitly out of scope): SnapStart, GraalVM native image, quota-increase request to AWS Support, Lightsail migration, any refactor that collapses services.

---

## 2. Pre-flight Checks (do these before any change lands)

Run these read-only verifications first. Each one validates an assumption used downstream.

| # | Check | Command / Location | Expected | If different, action |
|---|-------|--------------------|----------|----------------------|
| P1 | All four Dockerfiles use `amazoncorretto:25` (multi-arch manifest) | `grep -R "FROM amazoncorretto" *-service/Dockerfile api-gateway/Dockerfile` | 4 matches, all `amazoncorretto:25` | Audit any variant base image and confirm arm64 tag exists on Docker Hub |
| P2 | Lambda Web Adapter image is multi-arch | `docker manifest inspect public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0` | Manifest lists both `amd64` and `arm64` | Pin to a tag that does, or use `--platform` in COPY |
| P3 | Current `architectures` attribute is `["x86_64"]` on all 4 functions | `grep -n architectures infrastructure/terraform/modules/compute/main.tf` | 4 hits, all `x86_64` | Confirm scope; this plan assumes 4 flips |
| P4 | No JNI / native dependencies in any service JAR | `./gradlew :portfolio-service:dependencies` etc. — look for `netty-tcnative-boringssl-static`, `sqlite-jdbc-native`, LevelDB, RocksDB | No native deps flagged | If found, test arm64 replacement before flip |
| P5 | `CloudFrontOriginVerifyFilter` exemption list | `api-gateway/src/main/java/com/wealth/gateway/CloudFrontOriginVerifyFilter.java` | `/api/internal/**` exempt; `/actuator/**` NOT exempt | Confirmed — drives Phase 3 routing decision (warm api-gateway via CloudFront) |
| P6 | Market / insight gateway health routes exist | `curl -i https://vibhanshu-ai-portfolio.dev/api/market/health` and `/api/insight/health` | 200 or 404 | If 404: use `/actuator/health` on Function URLs directly for those two; api-gateway warms via CloudFront |
| P7 | GitHub Actions runner availability for arm64 | Check `ubuntu-24.04-arm` available on org runner tier | Available on public repos | If not: keep x86 runner, use `docker buildx build --platform linux/arm64` (QEMU, ~3× slower build) |
| P8 | Current AWS Cost Explorer baseline snapshot | AWS console → Cost Explorer → last 30 days, filter service = Lambda | Record $ value for post-change comparison | Needed for Objective 2 measurement |

**Gate:** All 8 checks must be logged before Phase 1 starts. File the results as a comment on the tracking PR.

### 2.9 Pre-flight outcomes (codebase-local checks, recorded 2026-04-22)

| # | Status | Evidence | Notes |
|---|--------|----------|-------|
| P1 | ✅ Pass | All four Dockerfiles declare `FROM amazoncorretto:25` (multi-arch manifest) | No Dockerfile edits needed for base image |
| P2 | ✅ Pass | `docker manifest inspect public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0` (2026-04-22): manifest list contains both `arm64/linux` (sha256:a9c70508…746919) and `amd64/linux` (sha256:cdf030be…b74b0). | BuildKit will auto-select arm64 variant when `--platform linux/arm64` is set. No Dockerfile changes needed. |
| P3 | ✅ Pass | `compute/main.tf:177, 220, 254, 290` all declare `architectures = ["x86_64"]` | 4 flips, single attribute each |
| P4 | ✅ Pass (formal) | `./gradlew :<svc>:dependencies --configuration runtimeClasspath` across all 4 services (2026-04-22) + `jar tf` inspection of fat JARs. JNI-bearing deps resolved: **(a)** `netty-transport-native-epoll:4.2.12.Final` — single-classifier per-build-host via Gradle variant resolution; current x86 builds bundle `linux-x86_64`, arm64 builds will bundle `linux-aarch_64` (verified: Dockerfile runs Gradle inside `amazoncorretto:25` multi-arch container, so `uname -m` reports target arch). **(b)** `netty-codec-native-quic:4.2.12.Final` — bundles ALL 5 classifiers (linux/osx/windows × x86_64/aarch_64) in the fat JAR. **(c)** `zstd-jni:1.5.6-10`, `at.yawk.lz4:lz4-java:1.10.1`, `snappy-java:1.1.10.7` — classifier-less JARs with all platform natives bundled internally, select at runtime. **(d)** `netty-resolver-dns-native-macos:osx-x86_64` — macOS-only, inert on Linux Lambda (dead weight both pre- and post-flip). **Not found:** jansi, jna, jnr-ffi, jffi, conscrypt, tcnative, brotli4j, rocksdbjni, lmdbjava, sqlite-jdbc. | **Critical prerequisite for Phase 1:** Gradle must run inside a target-arch container (already true — Dockerfile `FROM amazoncorretto:25 AS builder` + `docker buildx --platform linux/arm64`). Do NOT run Gradle on the CI host and then copy artifacts into the image. |
| P5 | ✅ Pass (with nuance) | `CloudFrontOriginVerifyFilter.java:62` exempts **only** `/api/internal/**`. `SecurityConfig.java:33` `permitAll()` for `/actuator/**` is Spring Security/JWT only — runs AFTER the origin filter (`HIGHEST_PRECEDENCE`). | Warming api-gateway must go through CloudFront OR a future `/api/internal/warm` endpoint |
| P6 | ❌ **Finding invalidates §7.1.1 as originally written** | Gateway routes (`application.yml:15-45`): `/api/portfolio/**`, `/api/market/**`, `/api/insights/**` (plural), `/api/chat/**`, `/api/internal/**`. `portfolio-service` has custom `/api/portfolio/health` (JWT-exempt). `market-data-service` and `insight-service` have **no** gateway-routable health endpoint — only `/actuator/health` on their direct Function URLs. `/api/market/health` via gateway → hits `authenticated()` rule → 401. | Forces the reviewer's recommendation (direct Function URL warming) for market & insight. §7.1.1 updated. |
| P7 | ✅ Pass | `gh repo view` (2026-04-22): `visibility = PUBLIC`, `isPrivate = false`. GitHub-hosted Linux arm64 runners (`ubuntu-24.04-arm`, `ubuntu-22.04-arm`) are free and GA for public repositories since Jan 2025. `gh auth status` confirms token has `workflow` scope. | Use `runs-on: ubuntu-24.04-arm` directly in `.github/workflows/deploy.yml` for Phase 1 arm64 native build. No QEMU/cross-build fallback needed. |
| P8 | ✅ Pass (baseline recorded) | `aws ce get-cost-and-usage` 2026-03-22 → 2026-04-22 (account 844479804897, ap-south-1): Lambda line = **$0.00045 over 21 days (~$0.00065/mo extrapolated)**. Overall spend ~$0.002/mo (dominated by EC2-Other/NAT; credits offset most Data Transfer + WAF). Apr 1–22 window is `Estimated: true`. | Objective 2 measurement target: Lambda duration line should fall 15–20% post-arm64. Absolute $ delta is ~$0.00013/mo — the real value is Free-Tier headroom for warming's added 42K GB-s/mo. |

**Gate status (updated 2026-04-22):** **All 8 pre-flight checks complete.** 7 passes (P1, P2, P3, P4 formal, P5, P7, P8), 1 finding with fix applied (P6). **Phase 1 is unblocked.** Single prerequisite to preserve: Gradle resolution must happen inside a target-arch Docker stage (already the case; guarded by `FROM amazoncorretto:25 AS builder` + `docker buildx --platform linux/arm64`).

---

## 3. Phased Execution

Phases are independent where possible and sequenced where not. Each phase has a clear rollback.

```
Phase 0 ─── Pre-flight (§2)
              │
Phase 1 ─── ARM64 flip (Dockerfiles + Terraform + CI)    ◄── blocks Phase 2 PC consideration (out of scope here)
              │
Phase 2 ─── Warming infrastructure (EventBridge + IAM)   ◄── independent of Phase 3/4
              │
Phase 3 ─── CloudFrontOriginVerifyFilter exemption       ◄── optional; only if P6 forces direct-URL warming
              │
Phase 4 ─── Playwright E2E hardening (5 changes)         ◄── independent; can run in parallel with Phase 2
              │
Phase 5 ─── Observability (CloudWatch alarm)             ◄── final; locks in the safety net
```

Sections 4–8 detail each phase.

---

## 4. Phase 1 — ARM64 / Graviton2 Flip

**Goal:** Flip all four Lambda functions from `x86_64` to `arm64` without service impact.
**Estimated effort:** 1–2 hours (human) + 1 deploy cycle (CI).
**Expected savings:** ~20% on compute duration; opens the $15 PC envelope for later.

### 4.1 Dockerfile changes

The four Dockerfiles already use `amazoncorretto:25`, a multi-arch manifest (verified in P1). The only real change is **ensuring buildx builds the arm64 variant** — no Dockerfile edits are required for the base image.

The RIE install block in `api-gateway/Dockerfile:127-131` already branches on `uname -m`, so it is arch-neutral. Confirm the same block exists in the other three services (or is absent, which is also fine — RIE is only used for local runs).

### 4.2 CI build changes (`.github/workflows/deploy.yml` or equivalent)

Two options, pick one based on P7:

**Option A — native arm64 runner (preferred):**
```yaml
jobs:
  build-and-push:
    runs-on: ubuntu-24.04-arm   # native arm64
    steps:
      - uses: docker/setup-buildx-action@v3
      - run: |
          docker buildx build --platform linux/arm64 \
            --tag $ECR_REPO:$GITHUB_SHA --push \
            -f portfolio-service/Dockerfile .
```

**Option B — cross-build with QEMU (fallback):**
```yaml
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - run: docker buildx build --platform linux/arm64 --push ...
```

Option B adds ~3–5 minutes per service due to QEMU emulation of `./gradlew bootJar`. Acceptable if arm runners are unavailable.

### 4.3 Terraform change

Single attribute flip on all four `aws_lambda_function` resources in `infrastructure/terraform/modules/compute/main.tf` (lines 177, 220, 254, 290):

```diff
-  architectures = ["x86_64"]
+  architectures = ["arm64"]
```

Consider extracting to a module-level variable `var.lambda_architecture` with default `"arm64"` for future reversibility without touching four resources.

### 4.4 Deploy order (critical)

Because Lambda rejects an `update-function-code` call when the pushed image architecture does not match the function's declared `architectures`, these steps MUST happen in this order:

1. **Push arm64 images to ECR** (new tags — do not overwrite `:latest` until step 3).
2. **Invoke the deploy workflow** that calls `aws lambda update-function-code --image-uri $NEW_ARM64_URI` — this is allowed only if the function currently accepts the new arch. So:
3. **Terraform apply** `architectures = ["arm64"]` FIRST (with `ignore_changes = [image_uri]` keeping the old x86 image attached), then step 2.

Between steps 3 and 2, the function is declared arm64 but still points at the x86 image. It will fail cold-starts in this window (~2–5 min). Schedule during a low-traffic window, or run Terraform apply and the deploy workflow back-to-back from the same CI pipeline to minimise the gap.

### 4.5 Verification checklist (post-deploy)

| # | Check | How |
|---|-------|-----|
| V1 | `aws lambda get-function --function-name wealth-portfolio-service` returns `"Architectures": ["arm64"]` | CLI (× 4 functions) |
| V2 | First invocation returns 200, not `Runtime.InvalidEntrypoint` | Curl `/api/portfolio/health` via CloudFront |
| V3 | CloudWatch Logs free of `exec format error` | Log Insights query |
| V4 | `ci-verification.yml` passes on main | Actions dashboard |
| V5 | Cold-start `Init Duration` drops 5–15% vs. P8 baseline | Compare 10 cold-starts before/after |

### 4.6 Rollback

Revert `architectures` to `["x86_64"]` in all four resources, redeploy the x86 image. Total rollback time ≤ 10 min.


---

## 5. Phase 2 — Warming Infrastructure (EventBridge Scheduler)

**Goal:** Keep one execution environment warm per function, 24/7, for ~$0/month.
**Estimated effort:** 2–3 hours (Terraform + apply + verify).
**Scope:** New Terraform module `infrastructure/terraform/modules/warming/`.

### 5.1 Topology (final, reconciled with P5)

Per §2.1.1 of the analysis doc and Check P5 confirming `CloudFrontOriginVerifyFilter` does not exempt `/actuator/**`:

| Target | URL | Why |
|--------|-----|-----|
| api-gateway | `https://vibhanshu-ai-portfolio.dev/actuator/health` (through CloudFront) | Filter rejects direct Function URL hits on /actuator |
| portfolio-service | Function URL + `/actuator/health` (direct) | No filter; cheaper RTT |
| market-data-service | Function URL + `/actuator/health` (direct) | Same |
| insight-service | Function URL + `/actuator/health` (direct) | Same |

**Trade-off note on api-gateway:** Warming through CloudFront means the warming traffic also keeps the CloudFront edge cache entry fresh (harmless) but is subject to CloudFront's `Cache-Control` — `/actuator/health` must be excluded from CloudFront caching or warming will be served from edge and not reach the Lambda. A dedicated behavior path (or `Cache-Control: no-store` on the actuator response) is required. Alternative: add `/actuator/health` to the filter's exemption list (Phase 3, optional).

### 5.2 Terraform module structure

New directory: `infrastructure/terraform/modules/warming/`

Files:
- `main.tf` — scheduler group, 4 × `aws_scheduler_schedule`, IAM role, log group
- `variables.tf` — targets map (URL + method + headers), schedule expression, region
- `outputs.tf` — schedule ARNs, IAM role ARN

Module invocation in `infrastructure/terraform/main.tf`:

```hcl
module "warming" {
  source = "./modules/warming"
  count  = var.enable_warming ? 1 : 0

  region         = var.aws_region
  schedule_cron  = "rate(5 minutes)"

  targets = {
    api_gateway = {
      url    = "https://${var.cloudfront_domain}/actuator/health"
      method = "GET"
    }
    portfolio = {
      url    = "${module.compute.portfolio_function_url}actuator/health"
      method = "GET"
    }
    market_data = {
      url    = "${module.compute.market_data_function_url}actuator/health"
      method = "GET"
    }
    insight = {
      url    = "${module.compute.insight_function_url}actuator/health"
      method = "GET"
    }
  }
}
```

New root variable: `enable_warming` (bool, default `false` initially → flip to `true` after dry-run).

### 5.3 Core resources (sketch)

```hcl
resource "aws_scheduler_schedule_group" "warming" {
  name = "wealth-lambda-warming"
}

resource "aws_scheduler_schedule" "target" {
  for_each = var.targets

  name       = "warm-${each.key}"
  group_name = aws_scheduler_schedule_group.warming.name

  flexible_time_window { mode = "OFF" }
  schedule_expression = var.schedule_cron

  target {
    arn      = "arn:aws:scheduler:::http-invoke"
    role_arn = aws_iam_role.scheduler.arn

    http_parameters {
      http_method = each.value.method
      url         = each.value.url
    }

    retry_policy {
      maximum_event_age_in_seconds = 60
      maximum_retry_attempts       = 0   # warming is idempotent; don't retry
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name = "wealth-lambda-warming-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
}
```

EventBridge Scheduler's universal HTTPS target (`arn:aws:scheduler:::http-invoke`) requires no Lambda invoker — it is EventBridge-native. Added in Nov 2024 (GA).

### 5.4 Cost (upper bound)

- 4 targets × 12/hour × 24 × 30 = 34,560 HTTPS invocations/month.
- EventBridge Scheduler pricing: first 14M invocations/month free in ap-south-1. Well within.
- Lambda invocations caused: 34,560 × 4 functions ≈ 138,240/month. 1M free tier.
- Lambda duration: 138,240 × ~150 ms × 2048 MB = ~42,500 GB-s. 400K free tier.

**Total added cost: $0/month** while Free Tier holds.

### 5.5 Verification checklist

| # | Check | How |
|---|-------|-----|
| W1 | 4 schedules visible and `ENABLED` in console | EventBridge Scheduler → Schedule groups → wealth-lambda-warming |
| W2 | CloudWatch Logs for each Lambda show invocations at 5-min cadence | Log Insights: `filter @message like /GET \/actuator\/health/` |
| W3 | `ConcurrentExecutions` metric stays ≥ 4 during quiet periods | CloudWatch Metrics |
| W4 | No 4xx/5xx in scheduler DLQ (if configured) | Scheduler metrics |
| W5 | api-gateway warming actually reaches Lambda (not CloudFront cache) | Gateway access logs |

### 5.6 Rollback

`terraform destroy -target=module.warming` (or flip `enable_warming = false`). Schedules deleted within 1 min. No data loss.


---

## 6. Phase 3 — CloudFront / Filter Exemption (Optional)

**Goal:** Allow api-gateway warming to target its Function URL directly (skip CloudFront) for lower RTT and no cache-behavior carve-out.
**Estimated effort:** 30–60 min (single filter change + test).
**Decision gate:** Only execute if W5 in Phase 2 fails, i.e., CloudFront caching actually prevents warming from reaching the Lambda despite `Cache-Control: no-store`.

### 6.1 Change location

`api-gateway/src/main/java/com/wealth/gateway/CloudFrontOriginVerifyFilter.java`

Extend the existing exemption list:

```java
private static final List<String> EXEMPT_PATHS = List.of(
    "/api/internal/",
    "/actuator/health"   // ← add this
);
```

**Risk:** `/actuator/health` becomes publicly reachable on the api-gateway Function URL. This is benign if the endpoint only exposes `status: UP` (default Spring Boot actuator config), but must be verified.

### 6.2 Verification

- `curl https://<function-url>/actuator/health` returns 200 with body `{"status":"UP"}` — no sensitive detail leaked.
- `curl https://<function-url>/actuator/info` still returns 403 (filter still enforces on non-exempt paths).
- Existing CI gateway integration tests still pass.

### 6.3 Rollback

Remove the one line, redeploy. Warming topology reverts to routing api-gateway via CloudFront.

---

## 7. Phase 4 — Playwright E2E Hardening

**Goal:** Close the gaps the single-warm-instance topology introduces for synthetic monitoring. Ensures parallel page-level fetches and scheduler-tick drift do not cause false-red CI.
**Estimated effort:** 1–2 hours.
**Scope:** `frontend/playwright.config.ts`, `frontend/tests/e2e/global-setup.ts`, `.github/workflows/synthetic-monitoring.yml`.

### 7.1 Five targeted changes

#### 7.1.1 Fan-out pre-warm (`synthetic-monitoring.yml`)

Replace the single-endpoint curl loop at lines 32–51 with a parallel prime across all four services. Contract: any non-5xx within 65 s per endpoint = warm.

Per P6, `/api/market/health` and `/api/insights/health` do NOT exist — those paths hit the gateway's `authenticated()` rule and return 401 without ever invoking the downstream Lambda. The working topology mixes CloudFront-routed and direct-Function-URL endpoints:

| Target | URL | Path available? |
|---|---|---|
| api-gateway | `https://vibhanshu-ai-portfolio.dev/actuator/health` (via CloudFront, filter passes on `/actuator` when `X-Origin-Verify` header present — CF injects it automatically) | ✅ Yes |
| portfolio-service | `https://vibhanshu-ai-portfolio.dev/api/portfolio/health` (custom controller, JWT-exempt) | ✅ Yes |
| market-data-service | `${MARKET_DATA_FUNCTION_URL}/actuator/health` (direct, no filter, no JWT) | ✅ Yes |
| insight-service | `${INSIGHT_FUNCTION_URL}/actuator/health` (direct) | ✅ Yes |

Required CI secrets (added as repo secrets, sourced from Terraform outputs):
- `MARKET_DATA_FUNCTION_URL`
- `INSIGHT_FUNCTION_URL`

Workflow change:

```yaml
- name: Pre-warm all Lambda functions
  env:
    MARKET_DATA_FN_URL: ${{ secrets.MARKET_DATA_FUNCTION_URL }}
    INSIGHT_FN_URL:     ${{ secrets.INSIGHT_FUNCTION_URL }}
  run: |
    ENDPOINTS=(
      "https://vibhanshu-ai-portfolio.dev/actuator/health"       # api-gateway via CF
      "https://vibhanshu-ai-portfolio.dev/api/portfolio/health"  # portfolio via CF (custom controller)
      "${MARKET_DATA_FN_URL}actuator/health"                     # market-data direct Function URL
      "${INSIGHT_FN_URL}actuator/health"                         # insight direct Function URL
    )
    for url in "${ENDPOINTS[@]}"; do
      (for i in 1 2 3; do
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 65 "$url" || echo 000)
        if [ "$STATUS" != "000" ] && [ "$STATUS" -lt "500" ]; then exit 0; fi
        sleep 5
      done) &
    done
    wait
    echo "All services warmed (or pre-warm best-effort exhausted)"
```

**Note:** Function URL strings end with a trailing `/`, so the concatenation is `${URL}actuator/health`, not `${URL}/actuator/health`. Test the exact format with a single curl first.

#### 7.1.2 `retries: 1` for `aws-synthetic` project only

`frontend/playwright.config.ts` — add `retries: 1` inside the `aws-synthetic` project block. Keep `retries: 0` at root for local/Docker runs. Rationale: cold-start eviction is a known non-deterministic factor on AWS only; local stack failures are always real bugs.

#### 7.1.3 Warm-up phase in `global-setup.ts`

Prepend to `globalSetup()` a short serial touch of each downstream health endpoint when running against AWS. Adds ~5–10 s on the warm path; prevents seed-phase cold-starts.

```ts
if (process.env.BASE_URL?.includes("vibhanshu-ai-portfolio.dev")) {
  for (const svc of ["portfolio", "market", "insight"]) {
    try {
      await fetch(`${GATEWAY_BASE}/api/${svc}/health`, {
        signal: AbortSignal.timeout(65_000),
      });
    } catch { /* best-effort; seeding phase has its own retries */ }
  }
}
```

#### 7.1.4 Stagger page-level fan-out in `aws-synthetic` specs

Audit `frontend/tests/e2e/aws-synthetic/live-contract.spec.ts` and `ai-insights.spec.ts` for `Promise.all([...])` patterns on page-level fetches. Replace with sequential `await` pairs OR insert a 200–500 ms `waitForTimeout` between first-page-load and subsequent navigations. Goal: no single page triggers concurrent first-requests across multiple cold functions.

#### 7.1.5 Keep `workers: 1`

Already correct at `playwright.config.ts:12`. Document the rationale inline — ensure no future refactor raises it without considering the concurrency budget:

```ts
// workers: 1 — MUST stay at 1 while running against AWS. The account concurrency
// limit is 10; warming consumes 4 slots; page fan-out peaks at ~3. Raising this
// will cause ThrottleException or cold-start cascades. See:
// docs/architecture/lambda-stopgap-execution-plan.md §7.1.5
workers: 1,
```

### 7.2 Verification

| # | Check | How |
|---|-------|-----|
| E1 | `synthetic-monitoring.yml` green rate ≥ 95% over 14 days | GitHub Actions run history |
| E2 | Pre-warm step reports all 4 endpoints warm | Workflow log output |
| E3 | No test timeouts of 90 s in `aws-synthetic` project | Playwright HTML report |
| E4 | `ci-verification.yml` (local Docker stack) still passes | Actions dashboard — expect no change |
| E5 | Concurrent executions during a sync run never exceed 9 | CloudWatch Metrics during scheduled run |

### 7.3 Rollback

All five changes are additive or single-line. `git revert` the commit; no infrastructure state to unwind.


---

## 8. Phase 5 — Observability (Concurrency Guardrail)

**Goal:** Catch the failure mode where warming + real traffic + CI overlap and approach the 10-slot account limit.
**Estimated effort:** 30 min.
**Scope:** CloudWatch alarm added to the warming or compute module.

### 8.1 Alarm spec

```hcl
resource "aws_cloudwatch_metric_alarm" "concurrency_near_limit" {
  alarm_name          = "wealth-lambda-concurrency-near-limit"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 2
  metric_name         = "ConcurrentExecutions"
  namespace           = "AWS/Lambda"
  period              = 60
  statistic           = "Maximum"
  threshold           = 8                    # account limit is 10
  treat_missing_data  = "notBreaching"
  alarm_description   = "Account-wide concurrent executions ≥ 8 (limit 10). Investigate CI overlap or request quota increase."
  # No action — delivery via CloudWatch console / GitHub issue workflow.
  # Add an SNS topic here if email or Slack alerting is desired.
}
```

### 8.2 Verification

- Deliberately trigger: run `synthetic-monitoring.yml` while scheduler is mid-tick (~5 concurrent) and fire a manual curl wave of 4 concurrent requests. Alarm should go into `ALARM` state within 2 min.
- Resolution: alarm returns to `OK` once traffic subsides.

---

## 9. Sequencing and Dependencies

```
Phase 0 (Pre-flight)                     [0.5 h]
      │
      ├── P1-P8 results logged on tracking PR
      │
Phase 1 (ARM64 flip)                     [1-2 h]  ◄── gated on P1, P2, P4, P7
      │
      ├── Verify V1-V5 before proceeding
      │
Phase 2 (Warming infra)     ──┐          [2-3 h]  ◄── gated on Phase 1 V1-V5
      │                       │
Phase 4 (Playwright hardening)┘          [1-2 h]  ◄── can run in parallel with Phase 2
      │                                            (independent scope)
      │
Phase 3 (Filter exemption)               [0.5-1 h] ◄── only if Phase 2 W5 fails
      │
Phase 5 (Observability alarm)            [0.5 h]  ◄── final; locks safety net
      │
Stabilisation window: 14 days            [passive] ◄── E1 measurement
      │
Success: Objective 1-5 all green
```

**Total active effort:** 5.5–9 hours (excluding 14-day stabilisation).
**Feasible as:** one long afternoon OR three 2-hour sessions.

---

## 10. Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|------------|--------|------------|
| R1 | arm64 image has subtle JNI incompatibility not caught in P4 | Low | High — full outage | Rollback path §4.6, verify V2 immediately post-deploy |
| R2 | CloudFront caches `/actuator/health`, warming never reaches Lambda | Medium | Medium — warming ineffective for api-gateway only | W5 check; Phase 3 fallback |
| R3 | P6 shows `/api/market/health` routes do not exist | Medium | Low — pre-warm reduced to 2 of 4 services | Expose Function URLs to CI (Terraform outputs → workflow env) |
| R4 | EventBridge Scheduler universal HTTPS target not available in ap-south-1 | Low | Medium — warming re-design needed | Fallback: tiny warmer Lambda (~15 LoC) invoked by scheduler cron |
| R5 | Warming 138K invocations/month pushes over 1M free tier when combined with org load | Low | Low — small $ increase | Already have 14× headroom; monitor |
| R6 | `retries: 1` masks real regressions in aws-synthetic tests | Medium | Medium — defects escape | Scoped to aws-synthetic only (NOT root); review Playwright HTML report on each failed-then-passed retry |
| R7 | Scheduler-tick drift (last tick 4:59 ago) causes CI cold-starts despite warming | Medium | Low — already handled by E2E hardening | Phase 4 pre-warm fan-out |
| R8 | Account concurrency saturates during recruiter demo + scheduler tick + CI | Low | High — demo shows errors | Phase 5 alarm; request quota increase (free, AWS auto-approves) |

---

## 11. Cost Impact Summary

| Cost line | Before | After | Delta |
|-----------|--------|-------|-------|
| Lambda duration (x86 vs arm64) | ~$0 (Free Tier) | ~$0 (Free Tier) | Same $, but 20% headroom gained |
| Lambda invocations (warming adds 138K/mo) | ~10K/mo | ~148K/mo | Still 85% under 1M free tier |
| EventBridge Scheduler | $0 | $0 | 14M free invocations/mo |
| CloudWatch alarm | $0 | $0.10/mo | 1 alarm × $0.10 |
| CloudFront requests (warming adds 8.6K/mo) | ~current | ~current + 8.6K | Still under 10M/mo free tier (2026 tier) |
| **Total monthly delta** | **baseline** | **baseline + ~$0.10** | Negligible |

Post-Free-Tier projection (if Free Tier expires): ~$3–5/month with warming, still under the $10 preference.

---

## 12. Decisions Resolved (2026-04-22)

All five open questions have been answered. Decisions are captured below; the plan sections referenced have been updated to match.

| # | Question | Decision | Rationale |
|---|----------|----------|-----------|
| Q1 | Phase 1 deploy window — downtime vs. zero-downtime? | **Accept 2–5 min of potential cold-start failures; schedule during off-hours (late evening IST).** Zero-downtime alias/traffic-shift is overkill for a stopgap. | Portfolio project; primary users are self and recruiters; alias-plus-shift adds CI/Terraform complexity disproportionate to the risk. |
| Q2 | Phase 4.1.1 pre-warm — gateway routes vs. direct Function URLs? | **Direct Function URLs for market-data and insight** (via Terraform outputs → GitHub Actions secrets). api-gateway and portfolio-service warmed via CloudFront. | P6 confirms `/api/market/health` and `/api/insights/health` don't exist. Direct Function URLs also isolate warming from future gateway routing changes. §7.1.1 already updated. |
| Q3 | Phase 5 alarm action — SNS vs. console-only? | **SNS topic + email subscription.** Add Terraform resource in the warming module. | Free tier covers 1 K email notifications/month; meaningful proactive signal if concurrency approaches the 10-slot limit during a recruiter-triggered cold-start cascade. |
| Q4 | Phase 2 schedule frequency — 5 min vs. 3 min? | **Start at `rate(5 minutes)`.** Monitor `Init Duration` in CloudWatch for 48 h post-deploy. Drop to `rate(3 minutes)` only if evictions observed between ticks. | Standard baseline; still well within Free Tier if dropped to 3 min (230K invocations/mo). |
| Q5 | Tracking — one PR vs. PR per phase? | **One PR per phase** (5 PRs total: Phase 1, Phase 2, Phase 3 if triggered, Phase 4, Phase 5). | Clean rollback boundaries; commit history itself demonstrates risk-mitigated deployment discipline. |

### 12.1 Follow-on spec changes applied

- §7.1.1 pre-warm workflow: switched market-data and insight to direct Function URL targeting; added `MARKET_DATA_FUNCTION_URL` / `INSIGHT_FUNCTION_URL` as required CI secrets.
- §8.1 alarm spec: will gain an `alarm_actions` attribute pointing to an SNS topic ARN; new `aws_sns_topic` + `aws_sns_topic_subscription` resources in the warming module.
- §5.2 Terraform module scope: now includes the SNS topic + email subscription alongside the scheduler resources.

---

## 13. Ready-to-Execute Checklist

Tick these before asking me to start implementation:

- [x] Plan reviewed and approved (reviewer feedback captured 2026-04-22)
- [x] Pre-flight checks P1–P8 all complete (§2.9) — including P4 formal via Gradle + JAR inspection
- [x] Deploy window for Phase 1 agreed (off-hours, accept 2–5 min gap) — §12 Q1
- [x] Answers to §12 open questions recorded
- [x] Budget confirmation: all 5 phases stay within $10/month envelope ✓ (§11 confirms ~$0.10 delta + $0 SNS within Free Tier)
- [x] No scope creep into Lightsail, PC, or service refactor ✓ (§1 boundary stated)
- [ ] Tracking issue created (optional umbrella); Phase 1 PR branch created

---

## 14. References

- [`docs/architecture/lambda-vs-lightsail-analysis.md`](./lambda-vs-lightsail-analysis.md) — parent analysis, especially §2.1.1 (warming topology) and §2.2.1 (arm64 rationale).
- [`docs/changes/CHANGES_PHASE3_SUMMARY_2026-04-21.md`](../changes/CHANGES_PHASE3_SUMMARY_2026-04-21.md) — recent stability fixes, §6–7 describe the cold-start pain being addressed.
- `.github/workflows/synthetic-monitoring.yml` — current pre-warm baseline.
- `frontend/playwright.config.ts` — current E2E configuration.
- `infrastructure/terraform/modules/compute/main.tf` — arm64 flip target.
- `api-gateway/src/main/java/com/wealth/gateway/CloudFrontOriginVerifyFilter.java` — Phase 3 target.
