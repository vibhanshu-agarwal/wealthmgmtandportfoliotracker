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
