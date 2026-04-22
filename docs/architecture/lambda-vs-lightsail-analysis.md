# Lambda vs Lightsail: Deployment Cost & Feasibility Analysis

**Date:** 2026-04-22 (revised — PC cost table corrected, arm64 lever added and expanded, Lightsail IPv4 caveat added, warming topology §2.1.1 added)
**Budget constraint:** under $10 preferred, $15 hard maximum
**Workload:** 4 Spring Boot 4.0.5 microservices (api-gateway, portfolio-service, market-data-service, insight-service) deployed as container-image Lambdas behind CloudFront in ap-south-1.  
**External services (all free-tier):** Neon PostgreSQL, MongoDB Atlas, Upstash Redis, Aiven Kafka.

---

## 1. Problem Statement

The core question is not "can the app boot on Lambda?" — it can, and the configuration bugs documented in Sections 6–7 of `CHANGES_PHASE3_SUMMARY_2026-04-21.md` and Sections 8–10 of `CHANGES_PHASE3_INFRA_SUMMARY_18042026.md` have been resolved. The question is: **can it deliver predictable latency under the stated budget?**

Three separate concerns must be distinguished:

### 1.1 Configuration Bugs — RESOLVED

All of the following have been fixed and verified in production:

| Bug class         | Examples                                                                                                                                       | Status   |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| Truststore / SSL  | `kafka-truststore.jks` not committed; Redis SSL bundle misconfigured; Kafka auto-config class name stale                                       | ✅ Fixed |
| Dockerfile / LWA  | ENTRYPOINT pointed at LWA binary instead of Java; LWA binary filename mismatch (`aws-lambda-web-adapter` vs `lambda-adapter`); port mismatches | ✅ Fixed |
| Lambda config     | Timeout too low (30 s); missing `AWS_LWA_ASYNC_INIT`; invalid SnapStart handler; blank downstream URLs; missing readiness check path           | ✅ Fixed |
| Build pipeline    | AOT initializer missing (`.dockerignore` not excluding `build/`); legacy `cd.yml` overwriting ECR tags with Paketo images                      | ✅ Fixed |
| Env var ownership | `deploy.yml` full-replacing Lambda env vars, wiping Terraform-managed secrets; `SPRING_PROFILES_ACTIVE` missing `prod`                         | ✅ Fixed |

These are not ongoing risks. The document treats them as historical context only.

### 1.2 Throttling / Quota Pressure

The account concurrency limit in ap-south-1 is **10**. With 4 Lambda functions, each function can handle at most 1–2 concurrent requests before the account pool is exhausted. For a personal portfolio tracker with single-digit concurrent users, this is adequate for steady-state traffic. It becomes a problem only during:

- CI/CD E2E test runs that hit multiple endpoints simultaneously
- Cascading cold starts where api-gateway invokes portfolio-service (2 concurrent executions consumed)

Increasing the quota to 50–100 is possible via Service Quotas console (allow 2 weeks). This fixes throttling headroom but **does not remove cold starts**.

### 1.3 Structural Cold Starts — THE OPEN PROBLEM

Spring Boot 4 + AOT on 2048 MB Lambda via the Lambda Web Adapter pattern produces these cold-start times:

| Service             | Observed cold start | Notes                                          |
| ------------------- | ------------------- | ---------------------------------------------- |
| api-gateway         | ~10 s               | Lightest — no database connections             |
| portfolio-service   | ~25–30 s            | Flyway migrations + PostgreSQL connection pool |
| market-data-service | ~15–25 s            | MongoDB connection + optional hydration        |
| insight-service     | ~15–25 s            | Kafka + Redis + Bedrock client init            |

A user request that hits api-gateway (cold) → portfolio-service (cold) chains to **~40 s total latency**. The CloudFront origin-read timeout is set to 60 s (CDN module `origin_read_timeout = 60`), and the api-gateway downstream response-timeout is 55 s (`application-prod.yml`). So the request completes, but the UX is unacceptable for a demo/portfolio site.

**Why SnapStart cannot help:** SnapStart requires a real Lambda handler class to snapshot JVM state. The current deployment uses `handler = "not.used"` with the Lambda Web Adapter pattern — there is no Spring Cloud Function handler to snapshot. SnapStart is incompatible with this deployment model. (ARM64 SnapStart GA'd in July 2024, but the handler constraint is what disqualifies it here, not the architecture.)

**Spring AOT is already maxed out:** All four Dockerfiles run `processAot` via the `org.springframework.boot.aot` Gradle plugin, and the ENTRYPOINT passes `-Dspring.aot.enabled=true`. AOT reduces Spring context refresh time by ~20–40% but is **not** GraalVM native image — the JVM still loads classes, opens JDBC/Redis/Kafka connections, and runs Flyway at startup. The observed ~10–30 s cold starts **already reflect the full benefit of AOT**; there is no further tuning available on the JVM side without a GraalVM native-image migration, which is out of scope for this phase (Spring Cloud Gateway in the api-gateway has partial but not full native-image readiness in Spring Boot 4.0; migrating all four services would be a multi-week effort and is better tracked as a Phase 4 item).

---

## 2. Lambda Analysis: Can It Be Fixed?

### 2.1 Lambda As-Is / Low-Cost Mitigations (< $2/month)

**Scheduled warming** is the cheapest mitigation. A CloudWatch Events rule with `rate(5 minutes)` targeting each Lambda Function URL keeps at least one execution environment warm per function.

| Mitigation                         | Effect                                                                                                                                    | Cost                                                               |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Scheduled warming (5-min interval) | Keeps 1 warm instance per function; first-hit latency drops to ~0 s for single-user traffic                                               | ~$0 (a few hundred extra invocations/month, well within Free Tier) |
| Memory right-sizing experiment     | Reducing from 2048 → 1536 MB saves ~$0 (Free Tier) but may increase cold-start time; increasing to 3008 MB may reduce cold-start by 2–5 s | $0 (Free Tier covers it)                                           |
| Request fan-out reduction          | api-gateway currently calls downstream services synchronously; reducing cascading cold starts by pre-warming the full chain               | Architectural change, no cost                                      |
| CI pre-warm step                   | Already implemented — hits `/api/portfolio/health` before E2E tests                                                                       | $0                                                                 |

**Verdict:** Scheduled warming makes Lambda viable for single-user demo traffic. The site will respond in <1 s for warm requests. Cold starts still occur if:

- Two functions are invoked simultaneously (warming only keeps 1 instance each)
- The warming interval is missed (EventBridge / CloudWatch Events is best-effort, not guaranteed)
- A new deployment invalidates all warm instances

**Total cost: ~$2/month** (Lambda Free Tier + ECR storage + Route 53 + CloudWatch Logs).

### 2.1.1 Scheduled Warming — Topology and Implementation

Warming is **per-function**: each Lambda function has its own execution environment, and hitting one function's endpoint only keeps *that* function's environment warm. A single cron target on api-gateway leaves portfolio-service, market-data-service, and insight-service cold. Three topologies are viable:

**Option A — Warm api-gateway only (minimal)**

- One EventBridge schedule → one HTTPS target hitting api-gateway `/actuator/health`.
- Spring Boot actuator `/health` is a local check; it does **not** call downstream services.
- **Result:** Only api-gateway stays warm. First user request still triggers cold starts on whichever downstream Lambda it fans out to.
- **Cost:** ~8,640 invocations/month × ~150 ms × 2 GB ≈ 2,592 GB-s (< 1% of the 400 K GB-s permanent Free Tier).
- **Verdict:** Shaves ~10 s off first-hit latency; the ~25–30 s portfolio-service cold start remains.

**Option B — Warm all four independently (recommended)**

- One EventBridge schedule → four HTTPS targets, each hitting a different Function URL's `/actuator/health`.
- Each function keeps one warm environment independently.
- **Concurrency impact:** all four targets fire simultaneously at `rate(5 minutes)` → 4 of your 10 concurrency slots consumed for ~200 ms per cycle. 6 slots remain free for real traffic during that window. Well within the 10-quota ceiling.
- **Cost math:** 4 × 8,640 invocations × 200 ms × 2 GB ≈ 13,824 GB-s/month (~3.5% of the 400 K Free Tier). Request count 34,560/month, inside the 1 M permanent Free Tier.
- **Verdict:** The configuration that actually pays off — all four services warm, first-hit latency sub-second end-to-end.

**Option C — Fan-out through api-gateway routes**

- Target **one Function URL** but hit paths that the gateway proxies downstream, e.g. `/api/portfolio/health`.
- Each call warms **api-gateway + the targeted downstream** in one shot.
- **Concurrency impact:** three simultaneous fan-out hits briefly consume 6 slots (3× api-gateway + 3× downstream).
- **Downside:** only works cleanly for `/api/portfolio/health` today — `/api/market/health` and `/api/insights/health` are **not** in the `JwtAuthenticationFilter` skip list or the `SecurityConfig.permitAll()` list. Using those paths would require a code change + redeploy to whitelist them (see "Filter reality check" below).
- **Verdict:** Tightly coupled to the gateway route table; fragile given the 10-concurrency ceiling. Prefer Option B.

#### Filter reality check (important)

Two filters in api-gateway gate how warming can reach it:

1. **`CloudFrontOriginVerifyFilter`** (`HIGHEST_PRECEDENCE`) rejects any request to the api-gateway Function URL that is missing the `X-Origin-Verify` header. Only `/api/internal/**` is exempted. **`/actuator/**` is NOT exempted**, so hitting the api-gateway Function URL `/actuator/health` directly returns **403 Forbidden**.
2. **`JwtAuthenticationFilter`** (`HIGHEST_PRECEDENCE + 2`) + `SecurityConfig` permit only: `/actuator/**`, `/api/auth/**`, `/api/portfolio/health`, `/api/internal/**`. Everything else requires a valid JWT.

Consequence: **api-gateway must be warmed through CloudFront, not via its Function URL**, unless you either (a) add a path-based exemption to `CloudFrontOriginVerifyFilter` for `/actuator/health`, or (b) configure the EventBridge target to inject the `X-Origin-Verify` header with the correct secret (pulled from Secrets Manager / a Terraform-passed value).

The downstream three services (**portfolio, market-data, insight**) have **no equivalent origin-verify filter**, so their Function URLs can be hit directly on `/actuator/health` without any header gymnastics.

#### Recommended warming topology

| Target             | How warmed                                                         | Header needed              |
| ------------------ | ------------------------------------------------------------------ | -------------------------- |
| api-gateway        | CloudFront `https://<domain>/actuator/health`                      | Injected automatically by CF |
| portfolio-service  | Function URL `https://<url>/actuator/health`                       | None                       |
| market-data-service| Function URL `https://<url>/actuator/health`                       | None                       |
| insight-service    | Function URL `https://<url>/actuator/health`                       | None                       |

Alternative for api-gateway: warm via CloudFront `/api/portfolio/health` — this uses the existing whitelist entry in `JwtAuthenticationFilter` and `SecurityConfig`, and has the side-effect of also exercising the gateway's WebClient → portfolio routing code path (useful signal if that path breaks silently). But since we're already warming portfolio directly in Option B, hitting `/actuator/health` on the gateway is cleaner.

#### Terraform shape (EventBridge Scheduler + HTTPS targets)

Sketched at a high level — actual implementation deferred until you decide on the path:

```hcl
# A single schedule group keeps the four schedules discoverable and easy to pause.
resource "aws_scheduler_schedule_group" "warming" {
  name = "wealth-warming"
}

# One schedule per target. Uses EventBridge Scheduler's universal HTTPS target
# (target.arn = "arn:aws:scheduler:::http-invoke") to hit the Function URL /
# CloudFront domain directly — no tiny warmer Lambda required.
resource "aws_scheduler_schedule" "warm_<service>" {
  name                         = "warm-<service>"
  group_name                   = aws_scheduler_schedule_group.warming.name
  schedule_expression          = "rate(5 minutes)"
  schedule_expression_timezone = "UTC"
  flexible_time_window { mode = "OFF" }

  target {
    arn      = "arn:aws:scheduler:::http-invoke"
    role_arn = aws_iam_role.scheduler_http.arn
    http_parameters {
      path_parameter_values   = ["/actuator/health"]
      # For api-gateway via CloudFront: no header needed.
      # For api-gateway via Function URL: header_parameters = { X-Origin-Verify = var.cloudfront_origin_secret }
    }
    # endpoint = <CloudFront domain> or <Function URL>
  }
}

# CloudWatch alarm on ConcurrentExecutions with threshold 8 — early warning
# if warming + real traffic start to saturate the 10-slot account quota.
resource "aws_cloudwatch_metric_alarm" "concurrency_headroom" { ... }
```

#### Failure modes and mitigations

| Failure mode                                          | Mitigation                                                                                 |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| EventBridge cycle missed (best-effort scheduling)     | Accept — next cycle 5 min later; real traffic still works, just cold the first request     |
| Concurrent real traffic evicts the warmed environment | Add a second warm instance via Provisioned Concurrency (see §2.2); out-of-scope for free warming |
| Deployment rollout discards warm environments         | Run a post-deploy warm-up curl in `deploy.yml` (already partially implemented)             |
| CloudFront origin secret rotates                      | EventBridge target pulls secret via Secrets Manager; rotation updates without TF change    |
| Lambda concurrency quota saturated during warming     | CloudWatch alarm on `ConcurrentExecutions ≥ 8`; stagger warming schedules by 30–60 s       |
| `/actuator/health` endpoint fails (DB/Kafka unhealthy) | Health endpoint returns 503 but Lambda still executed, so environment is still warmed; alarm on 5xx separately |

### 2.2 Lambda with Stronger Guarantees (Provisioned Concurrency)

Provisioned concurrency (PC) pre-initializes execution environments, eliminating cold starts entirely.

**Quota prerequisite:** The account concurrency limit in ap-south-1 is 10. AWS requires at least 10 unreserved concurrent executions at all times. PC counts against the account pool. With a limit of 10, the maximum PC allocation is `10 − 10 = 0`. The quota must first be raised to at least `10 + N_PC_instances` (e.g. 12 for 2 PC instances). Quota requests are free — you pay only for what you actually use — but approval is not guaranteed and typically takes up to 2 weeks.

**Pricing rates** (ap-south-1, rounded from the AWS Lambda pricing page):

| Rate                                    | x86_64              | arm64 (Graviton2)  |
| --------------------------------------- | ------------------- | ------------------ |
| PC reserved (idle) per GB-second        | $0.0000041667       | ~$0.0000033334     |
| PC invocation duration per GB-second    | $0.0000097222       | ~$0.0000077778     |
| Per-request charge                      | $0.20 per 1 M       | $0.20 per 1 M      |

ARM64 (Graviton2) is ~20% cheaper than x86_64 for both reserved and invocation duration. See §2.2.1 for the architecture-change path.

**Monthly idle cost for one always-on PC instance** (730 h × GB × 3600 s × rate):

| Config (1 PC instance, 730 h)     | x86_64 idle cost | arm64 idle cost |
| --------------------------------- | ---------------- | --------------- |
| 1024 MB                           | **$10.95**       | **$8.76**       |
| 1536 MB                           | $16.43           | $13.14          |
| 2048 MB                           | $21.90           | $17.52          |

(Invocation-duration charges for low-traffic single-user workloads are cents/month and ignored here; per-request charges stay inside the 1 M/month Free Tier.)

**Multi-function configurations vs the $15 hard ceiling** (PC idle cost + ~$2 baseline):

| Configuration                                      | Total monthly | Under $15?                    |
| -------------------------------------------------- | ------------- | ----------------------------- |
| 1 function × 1024 MB × x86_64                      | **~$13**      | ✅ Fits                       |
| 1 function × 1024 MB × arm64                       | **~$11**      | ✅ Fits with margin           |
| 1 function × 2048 MB × arm64                       | **~$19.50**   | ❌ Over ceiling               |
| 2 functions × 768 MB × arm64                       | **~$15**      | ⚠️ At the ceiling, no margin  |
| 2 functions × 1024 MB × x86_64                     | **~$24**      | ❌ Over ceiling               |
| 2 functions × 1024 MB × arm64                      | **~$19.50**   | ❌ Over ceiling               |
| 2 functions × 2048 MB × x86_64 (full hot path)     | **~$46**      | ❌ Far over ceiling           |
| 4 functions × 1024 MB × x86_64                     | **~$46**      | ❌ Far over ceiling           |

**Only one PC configuration cleanly fits the budget:** a single PC instance (api-gateway only) at 1024 MB. On arm64 this is ~$11/month; on x86_64 ~$13/month. portfolio-service and the other two remain on-demand — their cold starts still occur, but the hot-path first-hit latency the user sees is bounded by api-gateway staying warm. This is a meaningful improvement over scheduled warming (which is best-effort), but it does not eliminate downstream cold starts.

**Trade-offs of single-function PC:**

1. Requires a quota increase (to ≥ 11 for 1 PC instance, ≥ 12 if reserving headroom for a second instance during deployments).
2. Memory must be reduced from the current 2048 MB → 1024 MB on the PC function. This may slightly increase *warm* invocation latency for CPU-bound paths, though Spring Cloud Gateway routing is I/O-bound and tolerant to this.
3. Only api-gateway is covered; portfolio-service cold start (~25–30 s) still occurs on the first request after idle timeout.
4. **Lambda Free Tier does not offset PC charges** — the Free Tier covers on-demand invocations and compute, not provisioned capacity.

### 2.2.1 ARM64 / Graviton2 as a Free Cost Lever

**ARM64 Lambda is ~20% cheaper** per GB-second than x86_64 at comparable (often better) Java cold-start performance. The savings apply to **both** on-demand and provisioned concurrency, and to every Lambda pricing table in this document.

#### Why it helps specifically for this workload

| Factor                       | x86_64                                  | arm64 (Graviton2)                                             |
| ---------------------------- | --------------------------------------- | ------------------------------------------------------------- |
| Per GB-s on-demand           | $0.0000166667                           | $0.0000133334 (~20% cheaper)                                  |
| Per GB-s PC reserved (idle)  | $0.0000041667                           | ~$0.0000033334 (~20% cheaper)                                 |
| Per GB-s PC duration         | $0.0000097222                           | ~$0.0000077778 (~20% cheaper)                                 |
| Per-request charge           | $0.20 per 1 M                           | $0.20 per 1 M (identical)                                     |
| Java cold-start (Corretto 25, 2 GB) | baseline                         | typically 5–15% faster for Spring Boot AOT workloads (JIT warm-up and class-loading benefit from Graviton2's higher IPC on Java bytecode) |
| Supported base images        | `amazoncorretto:25`, `public.ecr.aws/lambda/java:21`, etc. | same images; all first-party AWS Java base images are multi-arch |
| Lambda Web Adapter           | `public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0` | **same tag** — the image is a multi-arch manifest |
| SnapStart                    | supported (not usable here — LWA pattern) | supported since July 2024 (still not usable here — LWA pattern) |
| Memory range                 | 128 MB – 10,240 MB                       | 128 MB – 10,240 MB                                            |

#### Current state in this repository

- `infrastructure/terraform/modules/compute/main.tf` hardcodes `architectures = ["x86_64"]` on all four `aws_lambda_function` resources (api-gateway, portfolio-service, market-data-service, insight-service).
- All four Dockerfiles use `FROM amazoncorretto:25` (multi-arch manifest published by Amazon) as both the builder and jlink stages. No base-image change is required.
- The Lambda Web Adapter sidecar is pulled from `public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`, which is a multi-arch manifest — Docker will automatically select the `arm64` variant when building on an ARM builder.
- AOT initializer classes generated by `processAot` are pure JVM bytecode, entirely architecture-neutral.
- No service in the repo uses JNI, native libraries, or `System.loadLibrary` calls that would be architecture-specific. (Verified: no `*.so` or native artifacts under `*/src/main/resources` or build outputs.)
- ECR repositories accept multi-arch image manifests by default — no repo configuration change needed.

#### Migration path (step-by-step)

**Phase 1 — Build images on ARM (1–2 hours):**

Two approaches; pick one:

1. **Native ARM runners (recommended for CI):** GitHub Actions now offers `ubuntu-24.04-arm` (and `ubuntu-22.04-arm`) as first-class runners with no price premium on public repositories. Change `runs-on: ubuntu-latest` → `runs-on: ubuntu-24.04-arm` in the four build jobs in `.github/workflows/deploy.yml` (or whichever file builds the service images). No `docker buildx` setup required — native `docker build` produces an arm64 image in one pass. Build time is comparable to x86 for these workloads.
2. **`docker buildx` multi-arch (fallback for local builds):** `docker buildx build --platform linux/arm64 -t <service>:arm64 .` on an x86 host uses QEMU emulation. This works but is 3–5× slower and is only appropriate for occasional local verification, not CI.

**Phase 2 — Flip the Terraform architecture flag (5 minutes):**

```hcl
resource "aws_lambda_function" "api_gateway" {
  # ...
  architectures = ["arm64"]   # was: ["x86_64"]
  # ...
}
```

Apply the same change to `aws_lambda_function.portfolio`, `aws_lambda_function.market_data`, `aws_lambda_function.insight`. Terraform will update the functions in place; new invocations after the update use the arm64 image. (Terraform plan should show `~ architectures` as the only in-place diff per function.)

**Phase 3 — Push the arm64 image digest and deploy (5 minutes):**

The deploy pipeline's existing `aws lambda update-function-code --image-uri <digest>` step is architecture-agnostic — it just updates the image pointer. After the arm64 image is pushed to ECR, the next `deploy.yml` run picks it up. No changes to the deploy step itself.

**Phase 4 — Verify (15–30 minutes):**

Checklist:

- [ ] `aws lambda get-function --function-name wealth-api-gateway --query 'Configuration.Architectures'` returns `["arm64"]`.
- [ ] `aws lambda invoke` or a CloudFront curl against `/actuator/health` returns 200.
- [ ] CloudWatch Logs show Spring Boot startup log line and no `exec format error` or `cannot execute binary file` errors.
- [ ] Playwright E2E suite (`ci-verification.yml` aws-synthetic environment) runs green.
- [ ] Cold-start duration in CloudWatch (`Init Duration` metric) is same or lower than the x86 baseline. A small regression (≤ 1 s) would be surprising but not catastrophic; anything worse suggests the image wasn't actually built for arm64 and is running under x86 emulation.

#### Risks and mitigations

| Risk                                                 | Severity | Mitigation                                                                                 |
| ---------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------ |
| One of the Dockerfile stages accidentally pulls an x86-only image (e.g. `binutils` variant) | Low | All images used (`amazoncorretto:25`, `public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`, `aws-lambda-rie`) ship arm64 manifests; `aws-lambda-rie` download step is architecture-aware in the Dockerfile already |
| Transitive dependency with JNI native code           | Low      | Project uses only pure-Java dependencies (Spring Boot 4, Lettuce, Hikari, Flyway, Kafka client). None bundle architecture-specific native libs. Verify with `./gradlew :<service>:dependencies` if uncertain. |
| Cold-start regression on arm64                       | Very low | Empirical data from AWS Compute Blog and Java benchmarks shows Graviton2 is equal-or-faster for Spring Boot AOT workloads; if a regression appears, roll back is one `architectures = ["x86_64"]` + redeploy |
| CI runner cost change (if on private org)            | None on this repo | Public-repo ARM runners are free; private-repo pricing is slightly higher per minute, but build times are similar, so the net delta is minor |
| SnapStart availability                               | Not applicable | SnapStart is incompatible with the LWA pattern regardless of architecture — unchanged      |
| Base image security patches                          | None     | `amazoncorretto:25` multi-arch manifest is patched in lockstep for both architectures      |

#### Rollback plan

Single Terraform change reverts everything:

```hcl
architectures = ["x86_64"]
```

Apply + redeploy the existing x86 image digest (ECR keeps the old image for 30 days by default). Full rollback window: ~10 minutes.

#### Financial impact summary (at current ~$2/month baseline, no PC)

| Scenario                                             | x86_64     | arm64      | Saved     |
| ---------------------------------------------------- | ---------- | ---------- | --------- |
| Current on-demand usage (inside Free Tier)           | $0 compute | $0 compute | $0 today  |
| On-demand usage 2× Free Tier (800 K GB-s/mo)         | ~$6.67/mo  | ~$5.33/mo  | ~$1.33/mo |
| Single-function PC at 1024 MB (§2.2 option)          | $10.95/mo  | $8.76/mo   | $2.19/mo  |
| Two-function PC at 1024 MB (over ceiling either way) | $21.90/mo  | $17.52/mo  | $4.38/mo  |

**For this workload today (steady-state inside Free Tier), the dollar impact is $0 — but arm64 widens the "under $15" PC envelope and gives free headroom for traffic growth.**

#### Recommendation

**Switch to arm64 unconditionally**, regardless of which §5 recommendation you ultimately pick. It is a pure cost reduction with:

- Zero observable downside for this workload.
- No code changes required in any of the four services.
- A single Terraform line per function, plus a CI runner flag change.
- ~2–4 hours total effort including verification.
- Trivial rollback.

The arm64 flip is the one action worth taking **before** choosing between Lambda-stays, Hybrid, or Full-Lightsail, because it improves the economics of the first two options and costs nothing if you end up choosing Full-Lightsail anyway.

### 2.3 Lambda Verdict

| Question                     | Answer                                                                                                                                                                                                                                    |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Can Lambda be fixed as-is?   | **Mostly for reliability, only partially for UX.** Configuration bugs are fixed. Scheduled warming makes single-user demo traffic acceptable. Multi-user or guaranteed-latency scenarios remain unsolved without provisioned concurrency. |
| Can a quota increase fix it? | **Fixes throttling capacity, not cold-start latency.** A quota increase is free (pay-per-use), unblocks PC, but does not make first-hit latency predictable unless paired with PC (~$11–13/month extra for the single-function arm64/x86 option).                         |
| What is the cheapest Lambda option that eliminates the hot-path cold start? | **arm64 + 1 PC instance on api-gateway at 1024 MB ≈ $11/month all-in**, plus baseline ~$2 = **~$13/month**. Requires quota increase to ≥ 11. |

---

## 3. Lightsail Analysis

### 3.1 Why Lightsail Is Viable

The existing codebase already proves the services can run outside Lambda:

- **`docker-compose.yml`** runs all 4 services with `entrypoint: ["/opt/java/bin/java", "-jar", "/app/app.jar"]`, bypassing the Lambda Web Adapter entirely
- Each service is memory-limited to **768 MB** in Docker Compose and runs successfully
- The Dockerfiles produce standard Java applications — the LWA sidecar in `/opt/extensions/` is only loaded when the Lambda Extensions API is present
- **`application-prod.yml`** contains all cloud-agnostic production config (Kafka SASL, Redis URL, datasource URLs)
- **`application-aws.yml`** contains Lambda-specific overrides (disabling scheduled refresh, baseline seed, MongoDB health check) — a Lightsail deployment would use `prod` profile only, or a new `lightsail` profile that re-enables background work

### 3.2 Option A: Lightsail Instance ($7 or $12/month)

Run Docker Compose on a plain Linux VM.

> **Pricing caveats for ap-south-1 (Mumbai)** — verify on the Lightsail console before committing:
>
> - AWS began charging **$3.65/month per public IPv4** (Feb 2024). Lightsail Linux bundles have been re-tiered into "with public IPv4" vs dual-stack (IPv6-only) variants. The $7 / $12 figures below assume the IPv4-inclusive bundle; if AWS has split the IPv4 charge out in your region, add ~$3.65/month.
> - **Mumbai plans include half the data-transfer allowance** of US/EU plans (per `docs.aws.amazon.com`). The $12 "3 TB" plan provides ~1.5 TB/month in Mumbai — still comfortable for a demo-grade workload, but worth noting for future scale.
> - Data-transfer overage in Mumbai is **$0.09/GB** (up to $0.114 in some APAC regions), not the $10/GB figure that appears in older docs for inbound.

**$7/month plan (1 GB RAM, 2 vCPU burstable, 40 GB SSD, ~1 TB transfer in Mumbai):**

- 1 GB total RAM. After OS + Docker overhead (~300 MB), ~700 MB available for JVMs
- 4 services × 768 MB (current compose limit) = 3 GB — does not fit
- Would require merging services or aggressive heap limits (~128 MB each) — not viable for Spring Boot

**$12/month plan (2 GB RAM, 2 vCPU burstable, 60 GB SSD, ~1.5 TB transfer in Mumbai):**

- 2 GB total. After OS + Docker (~400 MB), ~1.6 GB for JVMs
- 4 services × 384 MB heap = 1.5 GB — tight but feasible with `-Xmx384m -Xms256m`
- Docker Compose already proves 768 MB per service is comfortable; 384 MB is a significant reduction but Spring Boot 4 with AOT can operate in this range for low-traffic workloads
- Burstable CPU: baseline is ~10% of 2 vCPU. Burst credits accumulate when idle and are consumed during cold starts and request processing. For a personal portfolio tracker, burst credits will rarely deplete

**Cost breakdown ($12 plan, IPv4-inclusive bundle):**

| Item                                                  | Monthly cost     |
| ----------------------------------------------------- | ---------------- |
| Lightsail instance ($12 plan, IPv4-inclusive)         | $12.00           |
| Route 53 hosted zone                                  | $0.50            |
| CloudFront (Free Tier, 1 TB egress included)          | $0.00            |
| External services (Neon, Atlas, Upstash, Aiven)       | $0.00            |
| **Total (IPv4 inclusive)**                            | **$12.50/month** |
| **Total (if IPv4 charged separately: +$3.65)**        | **$16.15/month** ⚠️ |

If the Mumbai bundle turns out to charge IPv4 separately, the $12 Lightsail option **exceeds the $15 hard ceiling by $1.15**. Mitigation: run dual-stack (IPv6-only origin behind CloudFront — CloudFront supports IPv6 origins) and skip the IPv4 add-on, which brings the total back to $12.50.

**Networking / TLS options:**

- **Option 1:** CloudFront → Lightsail static IP. CloudFront handles HTTPS termination. Lightsail instance runs Nginx on port 80 (HTTP) behind CloudFront. No Let's Encrypt needed.
- **Option 2:** Lightsail static IP + Let's Encrypt via certbot. Direct HTTPS without CloudFront. Loses CDN caching for static assets.
- **Recommended:** Option 1 — reuses existing CloudFront distribution, just change the API origin from Lambda Function URL to Lightsail IP (or IPv6-only hostname to avoid the IPv4 charge).

### 3.3 Option B: Lightsail Container Service ($7–$15/month)

Lightsail container services provide managed Docker hosting with a built-in HTTPS endpoint and load balancer.

**Nano ($7/month per node): 0.25 vCPU (shared), 512 MB RAM**

- A single Nano node must run all containers in the deployment
- 512 MB total for 4 Spring Boot JVMs — not viable even with aggressive tuning
- Could work for a single merged monolith JAR, but that requires significant refactoring

**Micro ($10/month per node): 0.25 vCPU (shared), 1 GB RAM**

- 1 GB for 4 JVMs — same problem as the $7 Lightsail instance
- 3 months free trial available
- Only viable if services are merged into 1–2 JVMs

**Small ($15/month per node): 0.5 vCPU (shared), 1 GB RAM**

- Same RAM as Micro, more CPU — doesn't solve the memory constraint
- At $15/month, hits the hard budget ceiling with no room for Route 53

**Container service limitations:**

- Each container service gets one public HTTPS endpoint — you'd need to designate one container as the public endpoint (api-gateway) and have it route to others internally
- Container services do NOT support Docker Compose — you define containers individually in the Lightsail console or API
- No SSH access to the underlying host
- 500 GB/month data transfer included

**Verdict:** Lightsail container services are not a good fit for 4 separate Spring Boot microservices at this budget. The RAM constraints are too tight without merging services.

### 3.4 Option C: Hybrid (Lightsail + Lambda)

Move the hot-path always-on services to Lightsail, keep colder/background services on Lambda.

**Configuration:**

- Lightsail $7/month instance: api-gateway + portfolio-service (the hot path that users hit on every page load)
- Lambda (Free Tier): market-data-service + insight-service (invoked less frequently, cold starts acceptable for AI insights and market data refresh)

**Cost:**

| Item                                      | Monthly cost     |
| ----------------------------------------- | ---------------- |
| Lightsail instance ($7 plan, 1 GB RAM)    | $7.00            |
| Lambda (market-data + insight, Free Tier) | $0.00            |
| Route 53                                  | $0.50            |
| CloudFront (Free Tier)                    | $0.00            |
| ECR storage (2 images)                    | $0.50            |
| **Total**                                 | **~$8.00/month** |

**Feasibility:**

- 1 GB RAM for 2 JVMs (api-gateway + portfolio-service): ~300 MB OS/Docker overhead, ~700 MB for 2 JVMs at 320 MB each — tight but viable
- api-gateway is the lightest service (no database, just routing + Redis rate limiting)
- portfolio-service needs PostgreSQL connection pool + Flyway — 320 MB heap is the minimum
- market-data and insight cold starts are acceptable because users don't hit them on every page load — they're triggered by explicit "refresh market data" or "get AI insight" actions

**Profile considerations:**

- Lightsail services would use `SPRING_PROFILES_ACTIVE=prod` (no `aws` profile needed — the `aws` profile disables background work that's useful on a long-lived VM)
- Lambda services continue using `SPRING_PROFILES_ACTIVE=prod,aws` (or `prod,aws,bedrock` for insight)
- CloudFront routes `/api/*` to the Lightsail instance; the api-gateway on Lightsail calls market-data and insight Lambda Function URLs directly

**Complexity:** This is the most complex option — two deployment targets, split CI/CD, mixed networking. But it's the only path that stays near $8/month while eliminating cold starts on the hot path.

### 3.5 Migration Effort Estimate

Based on actual repo structure and existing Docker/Compose setup:

| Task                            | Effort                                                  | Details                                                                                                                                                                                        |
| ------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Production compose override** | Low (1–2 hours)                                         | Create `docker-compose.prod.yml` that removes local infra services (postgres, mongodb, kafka, redis, ollama), points to external services via env vars, and tunes JVM heap limits (`-Xmx384m`) |
| **Profile cleanup**             | Low (1 hour)                                            | For full Lightsail: use `SPRING_PROFILES_ACTIVE=prod` (re-enables scheduled refresh, baseline seed). For hybrid: no change needed on Lambda side                                               |
| **Nginx reverse proxy**         | Medium (2–3 hours)                                      | If using CloudFront → Lightsail: Nginx on port 80 proxying `/api/*` to api-gateway container. If serving frontend too: serve `frontend/out/` static files from Nginx                           |
| **CI/CD pipeline**              | Medium (3–4 hours)                                      | New GitHub Actions job: build images, push to Lightsail instance via SSH/SCP or `docker compose pull`. Remove or gate Lambda deploy steps                                                      |
| **Terraform changes**           | Medium (2–3 hours)                                      | Add `aws_lightsail_instance` resource. For full migration: remove Lambda/ECR resources. For hybrid: keep market-data + insight Lambda resources                                                |
| **CloudFront origin update**    | Low (30 min)                                            | Change API origin from Lambda Function URL to Lightsail static IP in CDN module                                                                                                                |
| **DNS / static IP**             | Low (30 min)                                            | Attach Lightsail static IP, update Route 53 if not using CloudFront                                                                                                                            |
| **Testing & validation**        | Medium (2–3 hours)                                      | Verify all endpoints, E2E tests against new deployment                                                                                                                                         |
| **Total**                       | **1.5–2 days** (full Lightsail) / **2–3 days** (hybrid) |                                                                                                                                                                                                |

---

## 4. Decision Matrix

### Under $10/month (preferred)

| Option                             | Monthly cost | Cold starts                                         | UX                  | Effort   | Verdict              |
| ---------------------------------- | ------------ | --------------------------------------------------- | ------------------- | -------- | -------------------- |
| **Lambda + scheduled warming (arm64)** | ~$2      | Reduced (5-min warm interval, single-user only)     | Acceptable for demo | 1–2 h    | ✅ Cheapest path     |
| **Hybrid (Lightsail $7 + Lambda)** | ~$8          | None on hot path; occasional on AI/market endpoints | Good                | 2–3 days | ✅ Best UX under $10 |

### Under $15/month (hard maximum)

| Option                                                      | Monthly cost   | Cold starts           | UX   | Effort                          | Verdict                                                  |
| ----------------------------------------------------------- | -------------- | --------------------- | ---- | ------------------------------- | -------------------------------------------------------- |
| **Lightsail $12 instance (full, IPv4 inclusive)**           | ~$12.50        | None                  | Good | 1.5–2 days                      | ✅ Best balance if IPv4 stays bundled                    |
| **Lambda + arm64 + 1 PC instance on api-gateway (1024 MB)** | **~$13**       | None on api-gateway; downstream still cold on first hit | OK–Good | Low (quota + arch + TF; ~2–4 h) | ⚠️ Requires quota increase to ≥ 11 (2-week lead time)    |
| **Lambda + x86_64 + 1 PC instance on api-gateway (1024 MB)**| ~$13           | None on api-gateway; downstream still cold on first hit | OK–Good | Low (quota + TF; ~1–2 h)        | ⚠️ Same as above, ~20% more expensive than arm64 variant |
| **Lightsail container Micro**                               | $10 + $0.50    | None                  | Good | High (service merge required)   | ❌ Too much refactoring                                   |

### Not recommended

| Option                                                       | Why                                                                      |
| ------------------------------------------------------------ | ------------------------------------------------------------------------ |
| **Lambda + PC on 2 functions at any memory**                 | 2 × 1024 MB arm64 = ~$20; 2 × 1024 MB x86 = ~$24 — both over $15 ceiling |
| **Lambda + PC at 2048 MB (any count)**                       | ≥ ~$20/month per function — far over ceiling                             |
| **Lightsail container Nano**                                 | 512 MB — cannot run 4 Spring Boot JVMs                                   |
| **Lightsail $5 instance**                                    | 512 MB — same constraint                                                 |
| **Lightsail container Small**                                | $15/month + Route 53 = over ceiling                                      |
| **Lightsail $12 if IPv4 is charged separately**              | $16.15/month if dual-stack-only bundle is chosen — over ceiling unless you accept IPv6-only origin |
| **GraalVM native image migration (Phase 4)**                 | Correct long-term fix for Lambda cold start but multi-week effort; Spring Cloud Gateway native-image readiness incomplete in Spring Boot 4.0 |

---

## 5. Recommendations

### TL;DR

- **Cheapest, least disruptive:** Lambda + arm64 switch + scheduled warming — ~$2/month, 1–2 h work.
- **Cheapest that eliminates hot-path cold start without leaving Lambda:** Lambda + arm64 + 1 PC instance on api-gateway at 1024 MB — ~$13/month, blocked on quota increase to ≥ 11.
- **Cheapest that eliminates all cold starts:** Hybrid (Lightsail $7 + Lambda) at ~$8/month, or full Lightsail $12 at ~$12.50/month.
- **ARM64 is a free cost reduction regardless of which option is chosen.**

### Best under $10 (option 1): Lambda + arm64 + Scheduled Warming (~$2/month)

**Do this if cold starts are tolerable for a demo/portfolio site.**

- Change `architectures = ["x86_64"]` → `["arm64"]` on all four Lambda resources in `infrastructure/terraform/modules/compute/main.tf`; rebuild images on an ARM runner or via `docker buildx`.
- Add an EventBridge Scheduler (or CloudWatch Events) rule: `rate(5 minutes)` targeting each Lambda Function URL health endpoint.
- Cost: effectively $0 on top of current ~$2/month baseline.
- Keeps the entire existing infrastructure unchanged (apart from architecture flip).
- Cold starts still occur on deployment, after 15+ minutes of inactivity (if warming misses), or under concurrent load.
- Single-user demo traffic will see <1 s response times for warm requests.

### Best under $10 (option 2): Hybrid Lightsail $7 + Lambda (~$8/month)

**Do this if you want near-zero cold starts on the hot path while staying under $10.**

- Lightsail $7 instance for api-gateway + portfolio-service (always warm).
- Lambda Free Tier (keep on arm64) for market-data-service + insight-service (cold starts acceptable for infrequent AI/market calls).
- Most complex operationally — two deployment targets, split CI/CD.
- Migration effort: 2–3 days.

### Middle ground ($13/month): Lambda + arm64 + 1 PC instance on api-gateway

**Do this if the quota increase is feasible and you want to stay 100% on Lambda.**

- Request ap-south-1 concurrency quota increase to ≥ 11 (ideally 20 for headroom).
- Flip all four Lambdas to arm64 (see §2.2.1).
- Reduce api-gateway memory from 2048 → 1024 MB.
- Set `enable_provisioned_concurrency = true` for **api-gateway only** — the Terraform resource `aws_lambda_provisioned_concurrency_config.api_gateway` is already gated on this flag. Leave portfolio, market-data, insight on-demand.
- Effort: quota request + ~2–4 h of Terraform/CI work.
- Outcome: api-gateway first-hit latency becomes sub-second; portfolio-service cold start still occurs on the first downstream call per idle cycle (~25–30 s), but only once per warm-pool cycle rather than every cold request.
- Cost: ~$11 PC + ~$2 baseline = **~$13/month**. Under $15.

### Best under $15: Lightsail $12 Instance (~$12.50/month)

**Do this if cold starts must be eliminated on every path, not just api-gateway.**

- Provision a $12/month Lightsail instance (2 GB RAM, 2 vCPU, 60 GB SSD) — **confirm on the console** whether IPv4 is included or charged separately at $3.65/month. If charged separately, use a dual-stack / IPv6-only bundle with CloudFront fronting it to stay under $15.
- Run all 4 services via a production Docker Compose override with `-Xmx384m` heap limits.
- CloudFront origin changes from Lambda Function URL to Lightsail IPv4/IPv6 hostname.
- Always-warm containers — zero cold starts, predictable latency.
- Migration effort: 1.5–2 days.
- Risk: 2 GB RAM is tight; monitor with `docker stats` and be prepared to upgrade to $24/month (4 GB) if OOM occurs.

### Why the discarded options fail

| Option                                             | Failure mode                                                                                         |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| PC on ≥ 2 functions at 1024 MB (x86)               | ~$24/month — exceeds $15 ceiling                                                                     |
| PC on ≥ 2 functions at 1024 MB (arm64)             | ~$20/month — exceeds $15 ceiling                                                                     |
| PC at 2048 MB (any count, any architecture)        | ≥ ~$18–46/month — exceeds ceiling                                                                    |
| Lightsail container Nano/Micro                     | RAM too low for 4 JVMs without monolith merge — effort                                               |
| Lightsail $5 instance                              | 512 MB RAM — cannot run even 2 Spring Boot services                                                  |
| Lightsail $12 with separate IPv4 charge (IPv4 bundle) | $16.15/month — over ceiling unless dual-stack origin is used                                     |
| GraalVM native image migration                     | Correct long-term fix but multi-week effort; SCG native-image readiness incomplete in Boot 4.0 — track as Phase 4 |
| Full ECS Fargate                                   | ALB ($16/month) + Fargate tasks — minimum ~$30/month — cost                                          |
