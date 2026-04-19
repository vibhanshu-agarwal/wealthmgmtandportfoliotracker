# Phase 3 Infrastructure — 2026-04-19 (post-deploy investigation)

**Previous revision:** [CHANGES_PHASE3_INFRA_SUMMARY_18042026.md](./CHANGES_PHASE3_INFRA_SUMMARY_18042026.md) — Lambda timeout fix spec, AOT build wiring, Redis URL, infrastructure health logging, multi-cloud profile layering, Docker build reliability.

---

## Summary

This document covers the live AWS investigation and fixes applied on 2026-04-19 after the Lambda function continued crashing with `Extension.Crash` despite all spec fixes being merged.

---

## 1. Investigation — Live AWS debugging

All investigation was performed against the live `wealth-mgmt-backend-lambda` function in `ap-south-1` using the AWS CLI and CloudWatch Logs Insights.

### 1.1 Symptom

Every invocation produced:

```
INFO app is not ready after 2000ms url=http://127.0.0.1:8080/actuator/health
INFO app is not ready after 4000ms ...
INFO app is not ready after 6000ms ...
INFO app is not ready after 8000ms ...
Error: hyper_util::client::legacy::Error(SendRequest, hyper::Error(IncompleteMessage))
EXTENSION  Name: aws-lambda-web-adapter  State: Started  Events: []
EXTENSION  Name: lambda-adapter          State: Ready    Events: []
INIT_REPORT Init Duration: 9804ms  Phase: init  Status: error  Error Type: Extension.Crash
REPORT ... Max Memory Used: 4 MB
```

`Max Memory Used: 4 MB` with a 2048 MB allocation means the JVM process started but Spring Boot never loaded a single class.

### 1.2 Root causes found (in order of discovery)

| #   | Root cause                              | Evidence                                                                                                                                                                                                                       | Fix                                                                                                                                                             |
| --- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **AOT initializer missing from JAR**    | `APPLICATION FAILED TO START — AOT initializer could not be found` (local Docker run)                                                                                                                                          | `.dockerignore` added to exclude `**/build/` — pre-built JARs were being copied into Docker context, Gradle treated them as UP-TO-DATE and skipped `processAot` |
| 2   | **`REDIS_URL` missing from Lambda env** | `aws lambda get-function-configuration` showed no `REDIS_URL`; `spring.data.redis.url: ${REDIS_URL}` with no default caused Spring Boot to attempt `localhost:6379`, hanging for ~20s                                          | Set `REDIS_URL` directly via AWS CLI; added `:redis://localhost:6379` fallback to prod profiles                                                                 |
| 3   | **LWA binary named incorrectly**        | Logs showed two extension names: `aws-lambda-web-adapter` (filename) and `lambda-adapter` (registered name). The LWA binary registers itself as `lambda-adapter` internally; when the filename differs, Lambda sees a conflict | Renamed destination in all four Dockerfiles from `/opt/extensions/aws-lambda-web-adapter` to `/opt/extensions/lambda-adapter`                                   |
| 4   | **Legacy `cd.yml` workflow**            | `cd.yml` still had `on: push: branches: [main]` and ran `bootBuildImage` (Paketo buildpacks) on every merge, overwriting the `latest` ECR tag with a Paketo-built image that had no AOT classes                                | Changed trigger to `workflow_dispatch` only                                                                                                                     |

---

## 2. Fixes applied

### 2.1 `.dockerignore` — **`5dda746`**

Added root `.dockerignore` excluding `**/build/` and `**/.gradle/`. Without this, the CI runner's pre-built JAR was sent into the Docker build context. Gradle inside the builder stage saw the JAR as UP-TO-DATE and skipped `processAot`, producing a JAR without AOT initializer classes.

Also removed the redundant `Build api-gateway production JAR` step from `deploy.yml` — the Docker build handles compilation internally.

**Files changed:** `.dockerignore`, `.github/workflows/deploy.yml`

### 2.2 `REDIS_URL` — **`fc715b9`**

`REDIS_URL` was never synced to the Lambda environment (only to GitHub Actions secrets). `spring.data.redis.url: ${REDIS_URL}` with no default caused Spring Boot to attempt connecting to `localhost:6379` on Lambda, hanging for ~20 seconds and exceeding the LWA 9.8s async-init window.

**Immediate fix:** Set `REDIS_URL` directly on the Lambda function via AWS CLI.

**Code fix:** Added `:redis://localhost:6379` fallback to `spring.data.redis.url` in `application-prod.yml` for all three Redis-using services so a missing `REDIS_URL` fails fast (connection refused) rather than hanging on a TCP timeout.

**Files changed:** `api-gateway/src/main/resources/application-prod.yml`, `insight-service/src/main/resources/application-prod.yml`, `portfolio-service/src/main/resources/application-prod.yml`

### 2.3 LWA binary filename — **`fbc3ef1`**

The LWA binary (`/lambda-adapter` inside the ECR image) registers itself with the Lambda Extensions API under the name **`lambda-adapter`**. Lambda uses the **filename** in `/opt/extensions/` as the extension identifier. When the file was named `aws-lambda-web-adapter`, Lambda saw two different extension names and treated it as a conflict, causing `Extension.Crash`.

The [official LWA documentation](https://github.com/awslabs/aws-lambda-web-adapter) specifies:

```dockerfile
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/lambda-adapter
```

All four Dockerfiles had the wrong destination name. Additionally, `portfolio-service` and `market-data-service` were copying to `/lambda-adapter` (root) — the `AWS_LAMBDA_EXEC_WRAPPER` pattern for Zip deployments — instead of `/opt/extensions/`.

**Files changed:** `api-gateway/Dockerfile`, `portfolio-service/Dockerfile`, `market-data-service/Dockerfile`, `insight-service/Dockerfile`

### 2.4 Legacy `cd.yml` disabled — **`1dc1e29`**

`cd.yml` had `on: push: branches: [main]` and ran `./gradlew bootBuildImage` (Paketo buildpacks) on every merge to main. This overwrote the `latest` ECR tag with a Paketo-built image that had no AOT initializer classes. The deploy workflow then picked up this image and deployed it to Lambda.

Changed trigger from `push: branches: [main]` to `workflow_dispatch` only.

**File changed:** `.github/workflows/deploy.yml`

---

## 3. Key decisions

| Decision                                     | Rationale                                                                                                                                                                                              |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`.dockerignore` excludes `**/build/`\*\*   | Ensures Docker always runs a clean Gradle build inside the builder stage. Pre-built artifacts from the CI runner must never enter the Docker context.                                                  |
| **`REDIS_URL` fallback to `localhost:6379`** | A missing env var should fail fast (connection refused in <1s) not hang (TCP timeout ~20s). The correct fix is always to have `REDIS_URL` set — use `sync-secrets.sh --lambda` to keep Lambda in sync. |
| **LWA binary named `lambda-adapter`**        | The binary's internal registration name must match the filename. The official LWA docs are explicit about this.                                                                                        |
| **`cd.yml` disabled not deleted**            | Kept for historical reference. The active pipeline is `ci-verification.yml` which uses the custom multi-stage Dockerfiles.                                                                             |

---

## 4. Pending

The new image (`fbc3ef1`) with the correct LWA filename is building via the deploy pipeline triggered by the merge to `main`. Once deployed, the `Extension.Crash` should be resolved.

**Remaining known issue:** Kafka connectivity is broken due to a certificate issue (pre-existing, tracked separately). The `InfrastructureHealthLogger` will surface this as `[INFRA-FAIL] Kafka` on startup — expected and non-blocking.

---

## 5. Git record

- **Branch:** `architecture/cloud-native-extraction` → merged to `main`
- **Commits (2026-04-19):**
  - `5dda746` — `.dockerignore` + remove redundant pre-build step from `deploy.yml`
  - `fc715b9` — `REDIS_URL` fallback default in prod profiles
  - `1dc1e29` — disable legacy `cd.yml` (Paketo/bootBuildImage)
  - `fbc3ef1` — rename LWA binary to `lambda-adapter` in all four Dockerfiles
- **Main merge commits:** `079920a`, `aa1f129`, `e688aed`, `d91a4da`
- **Remote:** [github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker)

---

## 6. Lambda Environment Variable Ownership Consolidation (follow-up)

**Date:** 2026-04-19 (follow-up to sections 1–5 above)

### 6.1 Regression RCA

**Regression commit:** `2d06599` ("feat(infra): align CD with Image Lambda")

Commit `2d06599` rewrote `deploy.yml`'s Lambda environment block from a `cat <<EOF` heredoc to a `jq -n` builder. The new builder explicitly listed only 11 variables (infrastructure constants + api-gateway routing). Because `aws lambda update-function-configuration --environment` performs a **full replace** of the entire `Variables` map, every `deploy.yml` run silently wiped any variable not in the 11-key payload — including `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`, and `SPRING_DATASOURCE_*`.

Additionally, `SPRING_PROFILES_ACTIVE` was set to `"aws"` in Terraform's `common_env` (missing `"prod"`). Without the `prod` profile, `application-prod.yml` never loaded, so Spring Boot could not resolve `${REDIS_URL}`, `${KAFKA_BOOTSTRAP_SERVERS}`, etc. even when those env vars were present.

A third drift vector existed: `scripts/sync-secrets.sh --lambda` called `update-function-configuration` directly with a hardcoded 11-key payload, bypassing Terraform state entirely.

### 6.2 New Ownership Topology

```mermaid
flowchart LR
  gh["GitHub Secrets\n(REDIS_URL, KAFKA_*, etc.)"]
  tfyml["terraform.yml\n(push: main)"]
  deployyml["deploy.yml\n(push: main + extraction)"]
  tfstate["Terraform state\n(env vars — full set)"]
  lambda["Lambda functions\n(all four services)"]

  gh -->|"TF_VAR_*"| tfyml
  tfyml -->|"terraform apply\nsets environment.variables"| tfstate
  tfstate -->|"full env var set"| lambda
  deployyml -->|"update-function-code\n(image only)"| lambda
```

**Key principle:** Terraform is the single owner of the Lambda `Variables` map. `deploy.yml` is image-only — it calls `update-function-code` but never `update-function-configuration`.

### 6.3 Changes Applied

| File                                                    | Change                                                                                                                                                                           |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `infrastructure/terraform/variables.tf`                 | Added `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password` (sensitive where appropriate)                                                         |
| `infrastructure/terraform/main.tf`                      | Passed all four new variables to `module "compute"`                                                                                                                              |
| `infrastructure/terraform/modules/compute/variables.tf` | Declared all four new variables in the compute module                                                                                                                            |
| `infrastructure/terraform/modules/compute/main.tf`      | Added `local.runtime_secrets` local; flipped `SPRING_PROFILES_ACTIVE` from `"aws"` to `"prod,aws"`; merged `runtime_secrets` into all four Lambda `environment.variables` blocks |
| `.github/workflows/terraform.yml`                       | Added `TF_VAR_redis_url`, `TF_VAR_kafka_bootstrap_servers`, `TF_VAR_kafka_sasl_username`, `TF_VAR_kafka_sasl_password` to env block                                              |
| `.github/workflows/deploy.yml`                          | Removed "Update Lambda function configuration" step and "Install jq" step; added `main` to push trigger (dual-branch); updated header comments                                   |
| `scripts/sync-secrets.sh`                               | Removed `--lambda` flag and `update-function-configuration` call; simplified to GitHub-only secret sync                                                                          |
| `infrastructure/terraform/terraform.tfvars.example`     | Added placeholder comments for new sensitive variables                                                                                                                           |
| `infrastructure/terraform/localstack.tfvars`            | Added stub values for LocalStack testing                                                                                                                                         |
| `.gitignore`                                            | Added `.env.secrets`, `*.env.secrets`, `app-inspect.jar`                                                                                                                         |
| `.env.secrets.example`                                  | Added `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` placeholders                                                                                       |
| `infrastructure/terraform/scripts/assert_plan.py`       | Enhanced `assert_spring_profiles_active` to validate value is `"prod,aws"` (not just present)                                                                                    |

### 6.4 Deprecation: `sync-secrets.sh --lambda`

The `--lambda` flag has been removed. Any direct `aws lambda update-function-configuration` call is now a foot-gun — it performs a full replace that drifts from Terraform-managed state.

**New workflow for updating Lambda env vars:**

1. Update the value in GitHub Actions secrets (via `./scripts/sync-secrets.sh .env.secrets` or `gh secret set`)
2. Push to `main` — `terraform.yml` will pick up the new `TF_VAR_*` value and apply it to the Lambda on the next run

### 6.5 Dual-Branch Deploy Trigger

`deploy.yml` now triggers on both `main` and `architecture/cloud-native-extraction`. Rationale: work happens on the feature branch and is periodically merged to `main`. Without the `main` trigger, a merge to `main` would not deploy the latest image, causing the two branches to diverge in deployed state.

### 6.6 First Apply Notes

The first `terraform apply` after this change will show an `environment.variables` diff for all four Lambda functions:

- `SPRING_PROFILES_ACTIVE` changes from `"aws"` to `"prod,aws"`
- `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` are added to all applicable functions

Review the plan output in `assert_plan.py` and the PR artifact before merging. Ensure `REDIS_URL` and `KAFKA_*` secrets exist in GitHub Actions before the apply runs.

---

## 7. Phase 4 — Service Split: Monolith to Four Independent Image Lambdas

**Date:** 2026-04-19 (continuation of Phase 3 work)

### 7.1 Overview

The single monolith Lambda (`wealth-mgmt-backend-lambda`, running only api-gateway) was split into four independently deployed Image Lambdas:

| Function                     | ECR Repository                  | Status                                          |
| ---------------------------- | ------------------------------- | ----------------------------------------------- |
| `wealth-api-gateway`         | `wealth-api-gateway` (existing) | ✅ UP — `{"status":"UP"}`                       |
| `wealth-portfolio-service`   | `wealth-portfolio-service`      | ✅ UP — `{"status":"UP"}`                       |
| `wealth-market-data-service` | `wealth-market-data-service`    | 🔄 Image rebuilding (MongoDB URI fix)           |
| `wealth-insight-service`     | `wealth-insight-service`        | ⏳ Alias + Function URL pending Terraform apply |

All four ECR repositories are in `ap-south-1` (same region as Lambda).

### 7.2 Architecture

```
CloudFront → wealth-api-gateway (Image Lambda, ap-south-1)
                ├── PORTFOLIO_SERVICE_URL  → wealth-portfolio-service Function URL
                ├── MARKET_DATA_SERVICE_URL → wealth-market-data-service Function URL
                └── INSIGHT_SERVICE_URL    → wealth-insight-service Function URL
                                                    ↓
                                           Amazon Bedrock (Claude 3 Haiku, us-east-1)
                                                    ↑↓
                                           Redis (Upstash, cache layer)
```

### 7.3 Terraform Changes

| File                              | Change                                                                                                                                                                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `infrastructure/terraform/ecr.tf` | New — three ECR repos in ap-south-1                                                                                                                                                                                                                     |
| `modules/compute/main.tf`         | Zip→Image for portfolio/market-data/insight; `SPRING_PROFILES_ACTIVE=prod,aws,bedrock` for insight; `PORTFOLIO_SERVICE_URL` wired to insight; ECR readonly IAM on all three roles; removed `reserved_concurrent_executions` (account limit is 10 total) |
| `modules/compute/variables.tf`    | Added `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri`, `postgres_username`, `postgres_password`; removed `s3_key_*`, `lambda_adapter_layer_arn`, `lambda_java_runtime`                                                              |
| `variables.tf`                    | Same additions at root level                                                                                                                                                                                                                            |
| `main.tf`                         | Removed S3 artifact bucket resources (bucket in us-east-1, all Lambdas now Image-based); wired new image URI and postgres credential vars to compute module                                                                                             |
| `terraform.yml`                   | Added `TF_VAR_*_image_uri` for three new services; `TF_VAR_postgres_username`, `TF_VAR_postgres_password`; removed S3/layer vars; added `aws_region=ap-south-1` to plan step                                                                            |

### 7.4 CI/CD Changes

**`deploy.yml`:**

- Builds and pushes all four service images sequentially
- Each Lambda update step checks if the function exists before calling `update-function-code` (graceful skip if not yet created by Terraform)
- `LAMBDA_FUNCTION_NAME_API_GATEWAY` replaces legacy `LAMBDA_FUNCTION_NAME`
- New secrets: `ECR_REPOSITORY_NAME_PORTFOLIO/MARKET_DATA/INSIGHT`, `LAMBDA_FUNCTION_NAME_PORTFOLIO/MARKET_DATA/INSIGHT`

### 7.5 Application Code Changes

**insight-service:**

- `build.gradle`: `spring-ai-starter-model-ollama` → `spring-ai-starter-model-bedrock-converse` (Ollama is local-only, incompatible with Lambda)
- `BedrockAiInsightService.java`: `@Profile("bedrock")`, `@Cacheable("sentiment")`, Claude 3 Haiku
- `BedrockInsightAdvisor.java`: `@Profile("bedrock")`, `@Cacheable("portfolio-analysis")`
- `CacheConfig.java`: `@EnableCaching`, `RedisCacheManager` (60/30 min TTLs), `CacheErrorHandler` for graceful Redis fallthrough
- `application.yml`: `REDIS_URL` replaces `SPRING_DATA_REDIS_HOST/PORT`; `spring.cache.type: simple` for local
- `application-prod.yml`: `spring.cache.type: redis`
- `application-aws.yml`: Bedrock model ID (`anthropic.claude-3-haiku-20240307-v1:0`) + region (`us-east-1`)
- `MockBedrockAiInsightService.java`: deleted (replaced by real implementation)

**portfolio-service:**

- `Dockerfile`: added `-Dspring.aot.enabled=true` to ENTRYPOINT (was missing)

**All four Dockerfiles:**

- `jlink` now includes `java.sql,java.naming` modules explicitly — required for PostgreSQL JDBC driver registration via ServiceLoader (without these, HikariCP reports "Driver claims to not accept jdbcUrl")

**market-data-service:**

- `application.yml`: `spring.mongodb.uri` → `spring.data.mongodb.uri` (key mismatch prevented `application-prod.yml` override from taking effect)

### 7.6 Bugs Encountered and Fixed During Service Split

| Bug                                                            | Root Cause                                                                                                                                                                                                         | Fix                                                                                                                                                 |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| ECR repos created in us-east-1                                 | Terraform `aws_region` defaults to `us-east-1`; plan step didn't pass `-var="aws_region=ap-south-1"`                                                                                                               | Added `-var="aws_region=ap-south-1"` to terraform.yml plan step; deleted and recreated repos in ap-south-1                                          |
| S3 bucket `PermanentRedirect`                                  | `wealth-artifacts-local` created in us-east-1 during failed apply; Terraform now runs with ap-south-1                                                                                                              | Removed S3 bucket resources from `main.tf` (no longer needed — all Lambdas are Image-based)                                                         |
| `PutFunctionConcurrency` error                                 | `reserved_concurrent_executions = 10` on all four Lambdas; account total limit is 10 (AWS requires ≥10 unreserved)                                                                                                 | Removed `reserved_concurrent_executions` from all Lambda resources                                                                                  |
| `assert_plan.py` false failures                                | `assert_all_lambda_functions_present` only checked active changes (create/update), missing stable no-op resources; `assert_spring_profiles_active` rejected `prod,aws,bedrock`                                     | Fixed to check all `resource_changes`; updated profile check to accept any value containing both `prod` and `aws`                                   |
| portfolio-service crash: "Driver claims to not accept jdbcUrl" | Custom JRE built via `jlink` was missing `java.sql` module; PostgreSQL JDBC driver uses ServiceLoader which requires `java.sql`                                                                                    | Added `java.sql,java.naming` to `jlink --add-modules` in all four Dockerfiles                                                                       |
| portfolio-service crash: missing datasource credentials        | `application-prod.yml` requires `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` separately from the JDBC URL                                                                                         | Added `postgres_username` and `postgres_password` Terraform variables; injected as `SPRING_DATASOURCE_USERNAME/PASSWORD` into portfolio Lambda env  |
| Neon JDBC URL format                                           | `.env.secrets` had `postgresql://user:pass@host/db` (libpq format) instead of `jdbc:postgresql://host/db` (JDBC format)                                                                                            | Updated to `jdbc:postgresql://ep-super-morning-a1l1lva1-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` with separate username/password |
| market-data MongoDB connecting to localhost                    | `application.yml` used `spring.mongodb.uri` (reads `SPRING_MONGODB_URI`) but `application-prod.yml` used `spring.data.mongodb.uri` (reads `SPRING_DATA_MONGODB_URI`) — different keys, prod override never applied | Changed `application.yml` to use `spring.data.mongodb.uri`                                                                                          |

### 7.7 Function URLs (ap-south-1)

| Service                      | Function URL                                                             |
| ---------------------------- | ------------------------------------------------------------------------ |
| `wealth-api-gateway`         | `https://lfhbpbwscoq7cmm5fcrgzy7edq0rxpos.lambda-url.ap-south-1.on.aws/` |
| `wealth-portfolio-service`   | `https://dyxr7lgmdhfo4gw4in2j4hl6ge0zhxor.lambda-url.ap-south-1.on.aws/` |
| `wealth-market-data-service` | `https://k3xpajgq7kpbsxrlp2bkq46bvm0dgjqw.lambda-url.ap-south-1.on.aws/` |
| `wealth-insight-service`     | `https://whqa2tes4rqq2yyjefk7zhaihe0afvhg.lambda-url.ap-south-1.on.aws/` |

api-gateway Function URL returns 403 on direct access (CloudFront origin secret filter) — invoke via AWS CLI or through CloudFront.

### 7.8 Verification Commands

```bash
# Test api-gateway (via AWS CLI — direct URL returns 403 due to CloudFront filter)
aws lambda invoke --function-name wealth-api-gateway --region ap-south-1 \
  --payload '{"rawPath":"/actuator/health","requestContext":{"http":{"method":"GET","path":"/actuator/health"}}}' \
  --cli-binary-format raw-in-base64-out /tmp/response.json && cat /tmp/response.json
# Expected: {"status":"UP"}

# Test portfolio-service
aws lambda invoke --function-name wealth-portfolio-service --region ap-south-1 \
  --payload '{"rawPath":"/actuator/health","requestContext":{"http":{"method":"GET","path":"/actuator/health"}}}' \
  --cli-binary-format raw-in-base64-out /tmp/response.json && cat /tmp/response.json
# Expected: {"status":"UP"}
```

### 7.9 Pending at Time of Writing

- `wealth-market-data-service`: image rebuild in progress (MongoDB URI key fix `acef405`)
- `wealth-insight-service`: alias + Function URL pending next `terraform apply` (insight Lambda exists but alias/URL not yet created due to concurrency errors in earlier apply runs)
- `wealth-mgmt-backend-lambda`: legacy monolith still exists — decommission after all four services confirmed healthy
- Neon duplicate database (`wealthmgmt-portfolio-db` created Apr 16) deleted; Apr 18 database retained

### 7.10 Key Git Commits (Phase 4)

| Commit    | Description                                                                               |
| --------- | ----------------------------------------------------------------------------------------- |
| `e9dc13a` | Phase 4 service split — all four services as Image Lambdas (main Phase A+B+C commit)      |
| `8714f67` | Fix: graceful Lambda skip in deploy.yml + fix integration test Redis URL                  |
| `086c9bf` | Fix: pass `aws_region=ap-south-1` to terraform plan                                       |
| `8cf3370` | Fix: remove S3 artifact bucket resources (PermanentRedirect)                              |
| `2200c8c` | Fix: remove `reserved_concurrent_executions` from all Lambdas                             |
| `6781cd8` | Fix: assert_plan.py for stable resources and bedrock profile                              |
| `9d3ca77` | Fix: remove `reserved_concurrent_executions` from insight Lambda (missed in previous fix) |
| `84e767c` | Fix: add `java.sql,java.naming` to jlink + postgres credentials to Lambda env             |
| `acef405` | Fix: `spring.data.mongodb.uri` key in market-data application.yml                         |

---

## 8. market-data-service: Four-Bug Fix Chain (2026-04-19, post-split)

**Date:** 2026-04-19 (follow-up to section 7)

After the Phase 4 service split, `wealth-market-data-service` continued crashing on every invocation. Four bugs were found and fixed in sequence.

### 8.1 Bug 1 — Wrong MongoDB env var key in `application.yml`

**Symptom:** `MongoClient` connected to `localhost:27017` despite `SPRING_DATA_MONGODB_URI` being set in Lambda env.

**Root cause:** `application.yml` used `${SPRING_MONGODB_URI:mongodb://localhost:27017/market_db}`. The Lambda env has `SPRING_DATA_MONGODB_URI` (not `SPRING_MONGODB_URI`), so the default `localhost:27017` was used. `application-prod.yml` overrides with `${SPRING_DATA_MONGODB_URI}` but the base value was already resolved.

**Fix:** Changed `application.yml` to `${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/market_db}`.

**Commit:** `883a8cb`

### 8.2 Bug 2 — `StartupHydrationService` crashes JVM on MongoDB timeout

**Symptom:** `StartupHydrationService.findAll()` blocked for 30s (MongoDB server selection timeout), exceeding the LWA 9.8s async-init window, crashing the JVM.

**Root cause:** `runHydration()` called `assetPriceRepository.findAll()` without catching exceptions. A MongoDB connection failure caused an uncaught exception that propagated up through `ApplicationRunner.run()` and crashed Spring Boot startup.

**Fix:** Wrapped `findAll()` in try/catch — logs WARN and skips hydration on failure.

**Commit:** `883a8cb`

### 8.3 Bug 3 — `jdk.naming.dns` missing from custom JRE (mongodb+srv:// SRV resolution)

**Symptom:** `MongoClientException: Unable to support mongodb+srv// style connections as the 'com.sun.jndi.dns.DnsContextFactory' class is not available in this JRE.`

**Root cause:** `jlink` built a custom JRE without `jdk.naming.dns`. The `com.sun.jndi.dns.DnsContextFactory` class (required for DNS SRV record lookup used by `mongodb+srv://` URIs) lives in `jdk.naming.dns`, not `java.naming`.

**Fix:** Added `jdk.naming.dns` to `jlink --add-modules` in `market-data-service/Dockerfile`.

**Commit:** `842f159`

### 8.4 Bug 4 — `MongoHealthIndicator` blocks LWA readiness check (AtlasError 8000)

**Symptom:** App started successfully (Tomcat on 8080, MongoDB Atlas connected) but LWA kept reporting "app is not ready" because `/actuator/health` returned DOWN. `MongoHealthIndicator` ran `hello` against the Atlas `local` database, which returned `AtlasError 8000: not authorized on local to execute command`.

**Root cause:** Spring Boot's `MongoHealthIndicator` runs a `hello` command against the `local` database by default. Atlas restricts access to the `local` db for all users — this is by design. The health check always fails, causing `/actuator/health` to return DOWN, which LWA interprets as "not ready".

**Attempted fixes that didn't work (AOT limitation):**

- `management.health.mongo.enabled: false` — evaluated at AOT build time via `@ConditionalOnEnabledHealthIndicator`, ignored at runtime
- `spring.autoconfigure.exclude: MongoHealthIndicatorAutoConfiguration` — also evaluated at AOT build time, ignored at runtime

**Fix that worked:** Override `AWS_LWA_READINESS_CHECK_PATH` to `/actuator/health/liveness` in Terraform for `wealth-market-data-service`. The liveness endpoint only checks that the JVM is alive, not external dependencies. LWA now marks the app as ready when the JVM is up, regardless of MongoDB health indicator status.

**Commits:** `a8a43f5`, `25afefe` (fmt fix)

### 8.5 Final State

| Service                      | Status                                                                |
| ---------------------------- | --------------------------------------------------------------------- |
| `wealth-api-gateway`         | ✅ UP                                                                 |
| `wealth-portfolio-service`   | ✅ UP                                                                 |
| `wealth-market-data-service` | ✅ Responding — `/actuator/health` returns DOWN (MongoHealthIndicator |
|                              | false-negative, Atlas restricts `local` db) but service is functional |
| `wealth-insight-service`     | ✅ UP                                                                 |
| `wealth-mgmt-backend-lambda` | 🗑️ Decommissioned (deleted)                                           |

### 8.6 Key Commits

| Commit    | Description                                                                 |
| --------- | --------------------------------------------------------------------------- |
| `883a8cb` | Fix MongoDB URI env var key + non-fatal hydration startup                   |
| `842f159` | Add `jdk.naming.dns` to jlink for `mongodb+srv://` SRV resolution           |
| `47b789d` | Disable MongoDB health indicator on Lambda (approach later superseded)      |
| `250dd81` | Exclude `MongoHealthIndicatorAutoConfiguration` (approach later superseded) |
| `a8a43f5` | Use `/actuator/health/liveness` for LWA readiness check (final working fix) |
| `25afefe` | `terraform fmt` fix for compute/main.tf                                     |
