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

---

## 9. Frontend: 401 Cascade on Stale Session Token

**Date:** 2026-04-19 (follow-up to section 8)

### 9.1 Symptom

Browser console on the `/login` page showed multiple `Failed to load resource: the server responded with a status of 401` errors for API endpoints (`/api/portfolio`, `/api/market/prices`, `/api/insights/market-summary`, etc.).

### 9.2 Root Cause

When a user visits the app with a stale/expired JWT in `localStorage`:

1. Root page (`/`) redirects to `/overview`
2. Dashboard components mount; `useAuthSession()` reads the stale token from `localStorage`
3. TanStack Query hooks fire with `enabled: true` (session exists) and the expired token
4. API gateway returns 401 for every request
5. `fetchWithAuthClient` threw a generic `Request failed (401)` error
6. TanStack Query logged the errors; user saw cascading 401s in the console
7. Auth check eventually redirected to `/login`, but the errors were already visible

### 9.3 Fix

Updated `frontend/src/lib/api/fetchWithAuth.ts` to intercept 401 responses:

- Calls `clearAuthSession()` to remove the stale token from `localStorage`
- Redirects to `/login` (unless already on `/login`) via `window.location.href`
- Throws `"Session expired"` (clean error message, prevents TanStack Query retry)

Updated `frontend/src/lib/api/fetchWithAuth.test.ts` to test the new 401 behavior (mock `localStorage`, assert session cleared).

### 9.4 Key Commit

| Commit    | Description                                                            |
| --------- | ---------------------------------------------------------------------- |
| `71a5716` | Handle 401 in fetchWithAuthClient — clear session + redirect to /login |

---

## 10. Kafka PKIX: SSL Trust Store Fix for jlink Custom JRE

**Date:** 2026-04-19 (follow-up to section 8)

### 10.1 Symptom

Kafka consumers on `wealth-portfolio-service` and `wealth-insight-service` failed with:

```
org.apache.kafka.common.errors.SslAuthenticationException: SSL handshake failed
Caused by: javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path building failed:
  sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

The Aiven Kafka broker (`kafka-wealthmgmt-wealthmgmt.i.aivencloud.com:23894`) uses SASL_SSL. The JVM could not validate the broker's SSL certificate.

### 10.2 Root Cause

The `jlink` custom JRE includes a `cacerts` keystore copied from the source JDK (`amazoncorretto:25`). However, the `amazonlinux:2023-minimal` runtime image ships without OS-level CA certificates, and the `jlink` output's `cacerts` was either incomplete or corrupted during the `--strip-debug --compress=zip-6` pipeline.

The JVM's JSSE (Java Secure Socket Extension) uses `$JAVA_HOME/lib/security/cacerts` as its trust store. Without the Aiven CA chain in that keystore, every SSL handshake to Aiven Kafka (and potentially MongoDB Atlas) failed.

### 10.3 Attempted Fixes

| Attempt | Approach                                                                            | Result                                                                                                             |
| ------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 1       | `microdnf install ca-certificates` in runtime image                                 | ❌ Populates OS trust store at `/etc/pki/tls/certs/ca-bundle.crt` but JVM doesn't read it — uses its own `cacerts` |
| 2       | `keytool -import` loop to import OS CAs into JRE `cacerts`                          | ❌ `keytool` not available in jlink custom JRE (requires `jdk.jartool` module which isn't included)                |
| 3       | `COPY --from=jre-builder /usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts` | ✅ Copies the full Corretto trust store directly into the custom JRE                                               |

### 10.4 Final Fix

All four Dockerfiles updated with two changes in the runtime stage:

1. **`ca-certificates` package** — `microdnf install -y tar gzip ca-certificates` (populates OS trust store for non-JVM tools like `curl`)
2. **Corretto `cacerts` copy** — `COPY --from=jre-builder /usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts ${JAVA_HOME}/lib/security/cacerts` (overwrites the jlink JRE's potentially incomplete `cacerts` with the full Corretto trust store)

### 10.5 Verification

After deploying the fix:

- **No more `SslAuthenticationException` or `PKIX path building failed`** errors in CloudWatch logs
- Kafka consumers on `portfolio-service` and `insight-service` successfully subscribed to topics (`market-prices`, `market-prices.DLT`)
- Kafka consumers disconnect after subscribing — this is a **separate issue** (likely SASL credential mismatch or Aiven service configuration), not related to SSL trust

### 10.6 Key Commits

| Commit    | Description                                                                 |
| --------- | --------------------------------------------------------------------------- |
| `eda27b6` | Add `ca-certificates` to all runtime images (OS trust store)                |
| `4a67114` | Import OS CA bundle into JRE cacerts via keytool (failed — keytool missing) |
| `748d4b5` | Replace keytool approach with direct Corretto cacerts COPY (working fix)    |

### 10.7 Files Changed

| File                             | Change                                      |
| -------------------------------- | ------------------------------------------- |
| `api-gateway/Dockerfile`         | `ca-certificates` + Corretto `cacerts` COPY |
| `portfolio-service/Dockerfile`   | `ca-certificates` + Corretto `cacerts` COPY |
| `market-data-service/Dockerfile` | `ca-certificates` + Corretto `cacerts` COPY |
| `insight-service/Dockerfile`     | `ca-certificates` + Corretto `cacerts` COPY |

---

## 11. Current State (end of 2026-04-19)

### 11.1 Service Health

| Service                      | Health        | Notes                                                                                                                          |
| ---------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `wealth-api-gateway`         | ✅ UP         | CloudFront → Function URL live                                                                                                 |
| `wealth-portfolio-service`   | ✅ UP         | Neon PostgreSQL connected, Flyway migrations applied                                                                           |
| `wealth-market-data-service` | ✅ Responding | `/actuator/health/liveness` = UP; `/actuator/health` = DOWN (MongoHealthIndicator false-negative — Atlas restricts `local` db) |
| `wealth-insight-service`     | ✅ UP         | Bedrock profile active, Redis (Upstash) connected                                                                              |
| `wealth-mgmt-backend-lambda` | 🗑️ Deleted    | Legacy monolith decommissioned                                                                                                 |

### 11.2 Known Remaining Issues

1. **Kafka consumer disconnect** — Consumers subscribe to topics but immediately disconnect. SSL handshake succeeds (PKIX fixed), so this is a SASL authentication or Aiven service configuration issue. Non-blocking for service health.
2. **MongoHealthIndicator false-negative** — `/actuator/health` returns DOWN on market-data-service because `MongoHealthIndicator` runs `hello` against Atlas `local` db (unauthorized). Mitigated by `AWS_LWA_READINESS_CHECK_PATH = /actuator/health/liveness`. A proper fix requires a custom `MongoHealthIndicator` bean that queries the application database instead of `local`.
3. **Spring Boot AOT limitations** — Several runtime property overrides (`management.health.mongo.enabled`, `spring.autoconfigure.exclude`) are ignored because AOT evaluates `@Conditional*` annotations at build time. Future work: either disable AOT for services with complex runtime profile switching, or move conditional logic to `@Bean` methods that AOT doesn't optimize away.

### 11.3 Spec Task Status

| Phase                              | Status                                     |
| ---------------------------------- | ------------------------------------------ |
| Phase A — Terraform Infrastructure | ✅ Complete                                |
| Phase B — Application Code         | ✅ Complete (B5.2 jqwik optional, skipped) |
| Phase C — CI/CD Pipeline           | ✅ Complete                                |
| Phase D — Property-Based Tests     | ⏳ Not started                             |
| Phase E — Verification             | ⏳ Not started                             |

### 11.4 Full Git Record (2026-04-19, all sessions)

| Commit    | Description                                                |
| --------- | ---------------------------------------------------------- |
| `5dda746` | `.dockerignore` + remove redundant pre-build step          |
| `fc715b9` | `REDIS_URL` fallback default in prod profiles              |
| `1dc1e29` | Disable legacy `cd.yml`                                    |
| `fbc3ef1` | Rename LWA binary to `lambda-adapter`                      |
| `e9dc13a` | Phase 4 service split (main A+B+C commit)                  |
| `8714f67` | Graceful Lambda skip in deploy.yml                         |
| `086c9bf` | Pass `aws_region=ap-south-1` to terraform plan             |
| `8cf3370` | Remove S3 artifact bucket resources                        |
| `2200c8c` | Remove `reserved_concurrent_executions`                    |
| `6781cd8` | Fix assert_plan.py for stable resources                    |
| `9d3ca77` | Remove `reserved_concurrent_executions` from insight       |
| `84e767c` | Add `java.sql,java.naming` to jlink + postgres credentials |
| `acef405` | Fix `spring.data.mongodb.uri` key in market-data           |
| `883a8cb` | Fix MongoDB URI env var key + non-fatal hydration          |
| `47b789d` | Disable MongoDB health indicator (superseded)              |
| `842f159` | Add `jdk.naming.dns` to jlink for SRV resolution           |
| `250dd81` | Exclude MongoHealthIndicatorAutoConfiguration (superseded) |
| `a8a43f5` | Use `/actuator/health/liveness` for LWA readiness          |
| `25afefe` | `terraform fmt` fix                                        |
| `1c2029a` | Changelog section 8                                        |
| `71a5716` | Frontend: handle 401 in fetchWithAuthClient                |
| `eda27b6` | Add `ca-certificates` to runtime images                    |
| `4a67114` | Import OS CA bundle via keytool (superseded)               |
| `748d4b5` | Direct Corretto cacerts COPY (final SSL fix)               |

---

## 12. Login 403 & Access Denied — CORS + JWT Filter + CloudFront Ops Fix

**Date:** 2026-04-19 (evening session)

### 12.1 Root Cause Analysis

Production users could not log in at `vibhanshu-ai-portfolio.dev`. Three interrelated issues:

1. **CORS rejection (403)** — `SecurityConfig.corsConfigurationSource()` hard-coded `setAllowedOrigins` to `http://localhost:3000` and `http://127.0.0.1:3000`. The production origin `https://vibhanshu-ai-portfolio.dev` was not in the list. Spring WebFlux CORS rejected the preflight with 403 before any auth logic ran.

2. **JWT filter blocking auth endpoints (latent 401)** — `JwtAuthenticationFilter` skip list only included `/actuator/**` and `/api/portfolio/health`. The `/api/auth/**` paths declared as `permitAll()` in SecurityConfig were missing. The filter's `switchIfEmpty` branch would return 401 for requests without a JWT. Masked by the CORS failure.

3. **CloudFront distribution not fully managed by Terraform** — The live distribution `E3EDXRGMYOSRB1` was in Terraform state but `domain_name` and `acm_certificate_arn` were never passed, so `aliases = []` and no ACM cert. The S3 origin domain used `ap-south-1` (Lambda region) instead of `us-east-1` (bucket region), causing S3 to return 301 redirects. The `X-Origin-Verify` custom header was missing from the API origin. The CF Function rewrite was attached but the S3 301s prevented it from working.

### 12.2 Code Fixes (api-gateway)

| File                           | Change                                                                                                                                                                                                     |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SecurityConfig.java`          | Replaced `setAllowedOrigins(List.of(...))` with `setAllowedOriginPatterns(allowedOriginPatterns)` injected via `@Value("${app.cors.allowed-origin-patterns:http://localhost:3000,http://127.0.0.1:3000}")` |
| `JwtAuthenticationFilter.java` | Extended skip-path condition to include `path.equals("/api/auth") \|\| path.startsWith("/api/auth/")` — reuses existing X-User-Id stripping branch (security guardrail)                                    |
| `application.yml`              | Added `app.cors.allowed-origin-patterns` with localhost defaults                                                                                                                                           |
| `application-prod.yml`         | Added `app.cors.allowed-origin-patterns` with `https://vibhanshu-ai-portfolio.dev` and `https://*.vibhanshu-ai-portfolio.dev`                                                                              |

### 12.3 Test Coverage

| Test Class                            | Tests | Purpose                                                                                                        |
| ------------------------------------- | ----- | -------------------------------------------------------------------------------------------------------------- |
| `CorsAndAuthBugConditionTest`         | 8     | Bug condition exploration — 3 CORS tests failed on unfixed code (confirmed bug), 5 auth/JWT tests passed       |
| `CorsAndAuthPreservationPropertyTest` | 14    | Preservation — localhost CORS, disallowed origins, authenticated endpoints, actuator skip, X-User-Id stripping |

All 22 tests pass after the fix. Full integration suite (`./gradlew :api-gateway:integrationTest`) green.

### 12.4 Infrastructure / Ops Changes

| Action                       | Detail                                                                                                                     |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `terraform.yml`              | Added `TF_VAR_domain_name` and `TF_VAR_acm_certificate_arn` from GitHub secrets                                            |
| Delete `modules/networking/` | Orphaned duplicate of `modules/cdn/` — root `main.tf` already uses `./modules/cdn`                                         |
| `variables.tf`               | Added `frontend_bucket_region` (default `us-east-1`) to fix S3 origin domain mismatch                                      |
| `main.tf`                    | Changed `static_site_bucket_regional_domain_name` from `${var.aws_region}` to `${var.frontend_bucket_region}`              |
| Terraform apply #1           | Added `X-Origin-Verify` custom header on API origin; alias + ACM cert already in state from prior apply                    |
| Terraform apply #2           | Fixed S3 origin domain from `ap-south-1` to `us-east-1`                                                                    |
| CloudFront invalidation      | `/*` invalidation after each apply                                                                                         |
| Secret alignment             | Verified `CLOUDFRONT_ORIGIN_SECRET` matches across Lambda env, GitHub secret, and CloudFront custom header (`865b515d...`) |

### 12.5 Post-Deploy Verification

| Probe                                       | Result                                                         |
| ------------------------------------------- | -------------------------------------------------------------- |
| `GET /`                                     | ✅ 200                                                         |
| `GET /login`                                | ✅ 200                                                         |
| `GET /overview`                             | ✅ 200                                                         |
| `GET /portfolio`                            | ✅ 200                                                         |
| `GET /market-data`                          | ✅ 200                                                         |
| `GET /settings`                             | ✅ 200                                                         |
| `POST /api/auth/login`                      | ✅ 200 — returns JWT token (fixed in 12.6–12.8)                |
| CORS preflight `OPTIONS /api/auth/login`    | ✅ 200 — `Access-Control-Allow-Origin` present (fixed in 12.8) |
| Direct Function URL without X-Origin-Verify | ✅ 403 (LURL security working)                                 |

### 12.6 Lambda Function URL 403 — Root Cause and Fix

**Symptom:** All four Function URLs returned `{"Message":"Forbidden"}` from Lambda's auth layer despite `AuthType: NONE` and `Principal: *` policy. AWS SDK invoke worked.

**Root cause:** The AWS Console adds **two** resource-based policy statements when creating a Function URL with `AuthType: NONE`:

1. `lambda:InvokeFunctionUrl` with `Principal: *` — Terraform had this
2. `lambda:InvokeFunction` with `Principal: *` — **Terraform was missing this**

Without both statements, Lambda's HTTP endpoint returns 403 `AccessDeniedException`. The SDK invoke works because it uses `lambda:InvokeFunction` via identity-based IAM policies, bypassing the resource-based policy.

**Discovery method:** Created a Function URL through the AWS Console on `wealth-insight-service` `$LATEST`, compared its policy to the Terraform-created one on the `live` alias. The console-created URL worked; the diff revealed the missing second statement.

**Fix:** Added `aws_lambda_permission` resources for both `InvokeFunctionUrl` and `InvokeFunction` to `modules/compute/main.tf` for all four functions. Also added the permissions via AWS CLI immediately for the `live` aliases.

**Commits:** `e2d55a3` (InvokeFunctionUrl), `a94db5b` (InvokeFunction)

### 12.7 CORS YAML Binding Fix

**Symptom:** After deploying the CORS code fix, the preflight still returned 403 with no CORS headers. The `@Value` injection wasn't picking up the production origins.

**Root cause:** `@Value("${app.cors.allowed-origin-patterns}")` with `List<String>` type cannot bind from YAML list syntax (`- "value1"`, `- "value2"`). YAML lists create indexed properties (`app.cors.allowed-origin-patterns[0]`) which `@Value` doesn't resolve to a `List`. It needs comma-separated string format.

**Fix:** Changed both `application.yml` and `application-prod.yml` from YAML list syntax to comma-separated strings:

```yaml
# Before (broken)
app.cors.allowed-origin-patterns:
  - "https://vibhanshu-ai-portfolio.dev"
  - "https://*.vibhanshu-ai-portfolio.dev"

# After (working)
app.cors.allowed-origin-patterns: "https://vibhanshu-ai-portfolio.dev,https://*.vibhanshu-ai-portfolio.dev"
```

**Commit:** `5e02301`

### 12.8 Lambda Alias Drift

**Symptom:** After deploying the CORS fix via `deploy.yml`, the Function URL still served the old code.

**Root cause:** `deploy.yml` calls `update-function-code` which updates `$LATEST`, but the Function URL is on the `live` alias. The alias was stuck on version 1 (the initial Terraform-created version). `deploy.yml` never publishes a new version or updates the alias.

**Fix (immediate):** Manually published new versions and updated aliases via AWS CLI:

```
wealth-api-gateway: live → v3
wealth-portfolio-service: live → v4
wealth-market-data-service: live → v6
wealth-insight-service: live → v5
```

**Permanent fix needed:** `deploy.yml` must publish a version and update the `live` alias after `update-function-code`. This is tracked as a follow-up.

### 12.9 Final Verification (end of session)

| Probe                         | Result                                                                                                          |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `GET /`                       | ✅ 200                                                                                                          |
| `GET /login`                  | ✅ 200                                                                                                          |
| `POST /api/auth/login` (curl) | ✅ 200 — JWT token returned                                                                                     |
| CORS preflight (curl)         | ✅ 200 — all CORS headers present                                                                               |
| Browser login                 | ✅ Working — redirects to dashboard                                                                             |
| Dashboard API calls           | ⚠️ 404/502 on `/api/portfolio/*`, `/api/market/*`, `/api/insights/*` — downstream service issues, not auth/CORS |

### 12.10 Key Commits (complete)

| Commit    | Description                                                                       |
| --------- | --------------------------------------------------------------------------------- |
| `618e3b3` | fix(api-gateway): CORS externalization + JWT filter skip for /api/auth/\*\*       |
| `0016b03` | ops: wire domain vars into terraform.yml, delete orphaned networking module       |
| `ebd3840` | ops: fix S3 origin region mismatch (ap-south-1 → us-east-1), changelog section 12 |
| `e2d55a3` | ops: add aws_lambda_permission for InvokeFunctionUrl                              |
| `a94db5b` | ops: add aws_lambda_permission for InvokeFunction (the missing second statement)  |
| `5e02301` | fix: comma-separated CORS patterns for @Value List binding                        |

### 12.11 Bugfix Spec

Full spec at `.kiro/specs/rca-login-403-and-access-denied/`:

- `bugfix.md` — 6 defect clauses, 6 expected-behavior clauses, 9 regression-prevention clauses
- `design.md` — formal bug condition specification, 5 correctness properties, fix implementation plan
- `tasks.md` — 4 top-level tasks (exploration tests → preservation tests → implementation → checkpoint)

---

## 13. Handoff — 2026-04-19 (end of evening session)

### 13.1 What's Working

- **Login flow**: ✅ `POST /api/auth/login` returns 200 with JWT through CloudFront
- **CORS**: ✅ Preflight returns proper headers for `https://vibhanshu-ai-portfolio.dev`
- **Static routes**: ✅ `/`, `/login`, `/overview`, `/portfolio`, `/market-data`, `/settings` all return 200
- **CloudFront**: ✅ Fully Terraform-managed — alias, ACM cert, CF Function rewrite, X-Origin-Verify header, correct S3 origin region
- **Lambda Function URLs**: ✅ All four accept HTTP requests (both permission statements in place)
- **Secret alignment**: ✅ CLOUDFRONT_ORIGIN_SECRET matches across Lambda, GitHub, CloudFront

### 13.2 What's Broken (dashboard API calls)

After login, the dashboard shows errors:

- `/api/portfolio/summary` → 404
- `/api/portfolio/holdings` → 404
- `/api/portfolio/performance` → 502
- `/api/market/prices` → 502
- `/api/insights/market-summary` → 502

These are **downstream service issues**, not auth/CORS. The api-gateway routes correctly but the backend services are returning errors. Likely causes:

- portfolio-service: 404 means the endpoints don't exist at those paths (check controller path mappings)
- market-data/insight: 502 means the services are crashing or timing out (check CloudWatch logs)

### 13.3 Critical Follow-Up: deploy.yml Alias Update

`deploy.yml` updates `$LATEST` but never publishes a version or updates the `live` alias. Every deploy requires manual alias update:

```bash
aws lambda publish-version --function-name <name> --query Version --output text
aws lambda update-alias --function-name <name> --name live --function-version <version>
```

This must be automated in `deploy.yml` to prevent alias drift.

### 13.4 Branch State

- **Branch:** `architecture/cloud-native-extraction` at `5e02301`
- **Main:** `5e02301` (in sync)
- **Terraform state:** S3 backend `vibhanshu-tf-state-2026` in `ap-south-1`
- **GitHub secrets:** All synced via `gh secret set -f .env.secrets` (includes `DOMAIN_NAME`, `ACM_CERTIFICATE_ARN`)

### 13.5 Lambda Alias Versions (current)

| Function                   | Alias `live` → Version |
| -------------------------- | ---------------------- |
| wealth-api-gateway         | v3                     |
| wealth-portfolio-service   | v4                     |
| wealth-market-data-service | v6                     |
| wealth-insight-service     | v5                     |

---

## 14. deploy.yml Alias Drift Fix — Automated publish-version + update-alias

**Date:** 2026-04-19 (follow-up to section 13.3)

### 14.1 Problem

`deploy.yml` called `aws lambda update-function-code` which updates `$LATEST`, but never published a new version or updated the `live` alias. Since Function URLs are attached to the `live` alias (not `$LATEST`), every deploy had zero effect on live traffic. Manual intervention was required after every push:

```bash
aws lambda publish-version --function-name <name> --query Version --output text
aws lambda update-alias --function-name <name> --name live --function-version <version>
```

This was identified in section 12.8 and tracked as a critical follow-up in section 13.3.

### 14.2 Root Causes

| #   | Root cause                                           | Impact                                                                                                                                                                                                    |
| --- | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **Missing `publish-version` in deploy.yml**          | After `update-function-code`, no immutable version was created from `$LATEST`. The `live` alias had nothing new to point at.                                                                              |
| 2   | **Missing `update-alias` in deploy.yml**             | Even if a version existed, the `live` alias was never updated. Function URLs continued serving the old version.                                                                                           |
| 3   | **Missing post-update wait loop**                    | Lambda code updates are asynchronous. Calling `publish-version` immediately after `update-function-code` risks `ResourceConflictException` if the update hasn't fully propagated.                         |
| 4   | **Missing Terraform `lifecycle` on alias resources** | Without `lifecycle { ignore_changes = [function_version] }` on `aws_lambda_alias` resources, `terraform apply` would revert the alias to the version Terraform last published, undoing deploy.yml's work. |

### 14.3 Fix — Terraform (must be applied first)

Added `lifecycle { ignore_changes = [function_version] }` to all 4 `aws_lambda_alias` resources in `infrastructure/terraform/modules/compute/main.tf`:

- `aws_lambda_alias.api_gateway_live`
- `aws_lambda_alias.portfolio_live`
- `aws_lambda_alias.market_data_live`
- `aws_lambda_alias.insight_live`

This matches the existing pattern on Lambda function resources (`lifecycle { ignore_changes = [image_uri] }`). Terraform owns the alias existence; deploy.yml owns the alias version.

**Validation:** `terraform fmt` clean, `terraform validate` passes.

### 14.4 Fix — deploy.yml

Each of the 4 "Update <service> Lambda" steps in `.github/workflows/deploy.yml` was extended with three additions after `update-function-code`:

1. **Post-update wait loop** — polls `LastUpdateStatus` (30 attempts, 5s sleep, abort on `Failed`) to ensure the code update has fully propagated before publishing. Same pattern as the existing pre-update loop.

2. **`publish-version`** — creates a new immutable version from `$LATEST`:

   ```bash
   NEW_VERSION=$(aws lambda publish-version \
     --function-name "$LAMBDA_FUNCTION_NAME" \
     --query 'Version' --output text)
   ```

3. **`update-alias`** — points the `live` alias at the new version:
   ```bash
   aws lambda update-alias \
     --function-name "$LAMBDA_FUNCTION_NAME" \
     --name live \
     --function-version "$NEW_VERSION"
   ```

All existing behavior preserved: pre-update wait loop, skip-if-not-exists check, ECR dual tagging (`latest` + SHA), no `update-function-configuration` calls.

### 14.5 Deployment Ordering

**Critical:** The Terraform lifecycle block changes (14.3) MUST be merged and applied via `terraform apply` BEFORE the new deploy.yml (14.4) runs in CI. Without this ordering, the next `terraform apply` after a deploy would revert the alias to the old version, creating a rollback race condition.

Sequence:

1. Merge Terraform changes → `terraform.yml` runs → `terraform apply` adds lifecycle blocks
2. Merge deploy.yml changes → next push triggers deploy → `publish-version` + `update-alias` execute
3. Future `terraform apply` runs see `ignore_changes = [function_version]` → no alias revert

### 14.6 Test Results

| Test                                   | Assertions | Result                                                                                                                                                         |
| -------------------------------------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Bug condition exploration (Property 1) | 12/12      | ✅ PASS — all 4 steps have `publish-version`, `update-alias`, and post-update wait loop                                                                        |
| Preservation (Property 2)              | 31/31      | ✅ PASS — ECR tagging, pre-update loop, skip-if-not-exists, no `update-function-configuration`, Docker flags, frontend deploy, Terraform aliases all preserved |
| `terraform validate`                   | —          | ✅ PASS                                                                                                                                                        |
| `terraform fmt -check`                 | —          | ✅ PASS                                                                                                                                                        |

### 14.7 Files Changed

| File                                                      | Change                                                                                          |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `infrastructure/terraform/modules/compute/main.tf`        | Added `lifecycle { ignore_changes = [function_version] }` to all 4 `aws_lambda_alias` resources |
| `.github/workflows/deploy.yml`                            | Added post-update wait loop + `publish-version` + `update-alias` to all 4 service update steps  |
| `scripts/tests/test-deploy-alias-update-bug-condition.sh` | Bug condition exploration test (shell-based workflow validation)                                |
| `scripts/tests/test-deploy-alias-update-preservation.sh`  | Preservation property test (31 assertions across deploy.yml + Terraform)                        |

### 14.8 Bugfix Spec

Full spec at `.kiro/specs/deploy-alias-update/`:

- `bugfix.md` — 5 defect clauses, 4 expected-behavior clauses, 6 regression-prevention clauses, formal bug condition specification
- `design.md` — root cause analysis, 2 correctness properties, fix implementation plan with bash code blocks
- `tasks.md` — 5 top-level tasks (exploration test → preservation test → Terraform fix → deploy.yml fix → checkpoint)

### 14.9 Post-Fix State

After this fix, every push to `main` or `architecture/cloud-native-extraction` will:

1. Build and push Docker images to ECR (tagged `latest` + commit SHA)
2. Call `update-function-code` to update `$LATEST`
3. Wait for code propagation (`LastUpdateStatus = Successful`)
4. Publish a new immutable version
5. Update the `live` alias to point at the new version
6. Function URLs immediately serve the new code

No manual alias updates required.
