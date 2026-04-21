# Phase 3 Stabilization — 2026-04-21

**Previous revision:** [CHANGES_PHASE3_SUMMARY_2026-04-20.md](./CHANGES_PHASE3_SUMMARY_2026-04-20.md) — Docker cache hardening, hydration crash mitigation, and Golden State Seeder backend architecture.

---

## Summary

This document covers the completion of the production security hardening and E2E test orchestration. Key focus areas were the consolidation of SSL/TLS certificate management, securing Redis connectivity for cloud-native environments, and implementing multi-layer secret protection via Gitleaks.

---

## 1. Security & Secret Protection — Gitleaks Hardening

### 1.1 Two-Layer Strategy
Implemented a robust defense-in-depth approach to prevent secret leaks (like the previously rotated MongoDB Atlas credentials).
- **Local (Layer 1)**: Integrated a `pre-commit` hook that executes Gitleaks locally. This prevents secrets from ever entering the git history on a developer's machine.
- **CI/CD (Layer 2)**: Added a Gitleaks scanning step to the GitHub Actions pipeline. If a secret bypasses the local hook, the CI build fails and blocks the merge.

### 1.2 Safeguards
- **.gitleaksignore**: Created to safely ignore the fingerprint of the previously leaked (and now rotated) credentials, preventing false positives while maintaining full protection for new commits.
- **.gitignore Hardening**: Broadened protection to strictly exclude all `.env.*` files, `.terraform.lock.hcl`, and `tfplan` artifacts.

---

## 2. Certificate Consolidation — Truststore Management

### 2.1 Centralized Resource
Moved the canonical `truststore.jks` into `common-dto/src/main/resources`. This serves as the single source of truth for CA certificates (Aiven, Upstash, etc.) across the entire modular monolith.

### 2.2 Generic Extraction Utility
Created the `TruststoreExtractor` utility in `common-dto`.
- **Purpose**: Libraries like the Kafka Java Client and Lettuce (Redis) require physical file paths for truststores and cannot read directly from a JAR's classpath.
- **Solution**: The utility extracts the JKS resource to the `/tmp` directory (available in AWS Lambda) at runtime and programmatically sets system properties (`KAFKA_TRUSTSTORE_PATH`, `REDIS_TRUSTSTORE_PATH`).
- **Implementation**: Integrated into all four microservice `Application` entry points to ensure connectivity is established before the Spring context finishes loading.

---

## 3. Production Redis Security — TLS/SSL Enforcement

### 3.1 Custom Lettuce Configuration
Implemented `RedisSslConfig` in `api-gateway` and `insight-service` to secure communication with Upstash Redis.
- **SSL Options**: Manually configured the Lettuce `SslOptions` to use the extracted truststore, resolving "PKIX path building failed" errors encountered in the containerized environment.
- **Protocol**: Updated `application-prod.yml` to strictly use `rediss://` for all production Redis URLs.
- **Spring Boot 4 Alignment**: Resolved package relocation issues for `LettuceClientConfigurationBuilderCustomizer` necessitated by the Spring Boot 4.0 upgrade.

---

## 4. E2E Test Hardening — Local & CI Reliability

### 4.1 Path Resilience
Updated `frontend/playwright.config.ts` to use absolute paths (via `path.resolve`) for `testDir` and `globalSetup`.
- **Impact**: Tests can now be discovered and executed correctly regardless of the current working directory (root or `frontend`).

### 4.2 Automated Secrets Loading
Enhanced `global-setup.ts` with a manual `.env.secrets` parser.
- **Fallback Logic**: If the `INTERNAL_API_KEY` is not present in the shell environment, the setup script now automatically searches for and parses the root `.env.secrets` file to extract `TF_VAR_internal_api_key`.
- **Result**: Restored the "Golden State" seeding functionality for local development environments where environment variables haven't been manually exported.

---

## 5. Deployment & Pipeline Status

### 5.1 CI Fixes
- Resolved a compilation failure in the `deploy-backend` job caused by untracked source files. Verified that all new security infrastructure (Extractor, SSL Config) is now correctly included in the ECR images.
- Recovered from a transient AWS ECR TLS handshake timeout by rerunning the `dba920b` deployment workflow.

### 5.2 Verification
| Component | Status | Verification Method |
|---|---|---|
| **API Gateway** | ✅ Live | Actuator /health (Deep Check) |
| **Redis TLS** | ✅ Secured | Handshake verified in Lettuce logs |
| **Kafka SSL** | ✅ Verified | Pre-flight connectivity script |
| **E2E Suite** | ✅ Green | 24 tests discovered and executed |

---

## Next Steps

1. **Monitor Synthetic Latency**: Observe the `aws-synthetic` test suite for any performance regressions related to the new SSL handshake overhead.
2. **Key Rotation Policy**: Move from manual `.env.secrets` management to AWS Secrets Manager as part of Phase 4 infrastructure scaling.

---

## 6. Production Stabilization — 502 Bad Gateway & Truststore RCA

### 6.1 Standardized Kafka Truststore
Identified a critical "Bad Gateway" (502) error in production caused by a `FileNotFoundException` during Kafka initialization.
- **Root Cause**: The native Kafka client requires a physical file path for the truststore. While we had a `truststore.jks`, a legacy configuration or library default was specifically looking for `kafka-truststore.jks` on the classpath.
- **Solution**: Standardized the naming convention across the entire project.
  - Renamed `truststore.jks` to `kafka-truststore.jks` in `common-dto`.
  - Updated all `TruststoreExtractor.extract` calls and `application-prod.yml` fallbacks to reference the new name.
  - Hardened `TruststoreExtractor` to use `java.io.tmpdir` and correctly format `file:` URLs for Spring Boot 4 compatibility.

### 6.2 CI/CD Secret Alignment
Resolved `403 Forbidden` and `Internal Server Error` responses during E2E database seeding.
- **Issue**: Secret name mismatch. The GitHub Action workflows were looking for `INTERNAL_API_KEY`, but the repository secret was named `TF_VAR_INTERNAL_API_KEY`.
- **Resolution**: Synchronized all `.github/workflows` (`terraform.yml`, `ci-verification.yml`, `synthetic-monitoring.yml`) and `docker-compose.yml` to use the correct secret mapping.

### 6.3 Repository Maintenance & Cleanup
- **Braintrust Cleanup**: Removed the unintended `.bt/` metadata folder from the git index and added it to `.gitignore` to maintain a clean repository structure.
- **Test Stabilization**: Updated all `TruststoreWiringTest` suites across all services to align with the new filename, ensuring the CI build remains green.
- **Gitignore Tuning**: Updated the truststore exclusion to point to the new `kafka-truststore.jks` location.

---

## 7. Cold-Start 502 / 504 Root Cause Chain — Full Resolution

Commits: `75d37e0` → `999624b` → `4582fdb` → `e067d73` → `6128776`

### 7.1 Root Cause Analysis

Investigation of persistent `502 Bad Gateway` errors after deployment uncovered a **four-layer failure chain** that only manifested on Lambda cold starts:

| Layer | Symptom | Root Cause |
|---|---|---|
| **Truststore missing from JAR** | `TruststoreExtractor` silently skipped extraction; Kafka SSL unconfigured | `kafka-truststore.jks` was `.gitignore`-d and never committed; CI Docker images built without it |
| **Redis SSL misconfiguration** | `api-gateway` startup crash on `application-prod.yml` parse | `spring.data.redis.ssl.bundle` requires a registered Spring Boot SSL bundle *name*, not a file path |
| **Kafka auto-config exclusion broken** | `NoSuchBeanDefinitionException` for `KafkaProperties` in integration tests | Spring Boot 4 moved `KafkaAutoConfiguration` to a new package; the old exclusion class name was a no-op, causing the real auto-config to run; excluding it removed the `KafkaProperties` bean needed by `InsightKafkaConfig` / `PortfolioKafkaConfig` |
| **Cold-start chain exceeds CloudFront timeout** | `504`/`502` on the first seeding request after deploy | api-gateway cold start (~10 s) + portfolio-service cold start (~30 s) = ~40 s total, dangerously close to CloudFront's 60 s origin-read limit |

### 7.2 Truststore File Committed to Version Control (`999624b`)

- `common-dto/src/main/resources/kafka-truststore.jks` was present locally but listed under a `.gitignore` negation rule that was never applied. The file was **never staged or committed**.
- CI Docker builds had no truststore → `TruststoreExtractor.extract()` logged a `WARN` and returned without setting `KAFKA_TRUSTSTORE_PATH` → Spring fell back to a classpath default that resolved to nothing → Kafka SSL was silently unconfigured → `502` on the first request.
- **Fix**: Committed the JKS file. It contains only Amazon's public CA certificates and carries no private keys or secrets.

### 7.3 Invalid Redis SSL Bundle Property Removed (`e067d73`)

- `application-prod.yml` for `api-gateway` contained `spring.data.redis.ssl.bundle: ${REDIS_TRUSTSTORE_PATH:}` alongside raw `trust-store-location` / `trust-store-password` keys.
- `spring.data.redis.ssl.bundle` expects a pre-registered Spring Boot SSL bundle *name* (from `spring.ssl.bundle.*`); passing a raw file path caused a startup `BeanCreationException` that crashed the gateway before it could serve any traffic.
- **Fix**: Removed the invalid bundle properties. Redis TLS is now configured exclusively through the `RedisSslConfig` `LettuceClientConfigurationBuilderCustomizer` bean introduced in Phase 3.

### 7.4 Spring Boot 4 Kafka Auto-Configuration Class Name (`75d37e0` + `4582fdb`)

Two successive fixes were required:

1. **Stale exclusion class name** (`75d37e0`): Six integration tests excluded `org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration` (the Spring Boot 3.x location). In Spring Boot 4 the class moved to `org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration`. The old name was a no-op exclusion, so Kafka auto-configuration ran in every test that was designed to skip it.

2. **Exclusion side-effect** (`4582fdb`): Once the correct class name was used, excluding `KafkaAutoConfiguration` entirely removed the `KafkaProperties` bean from the context. `InsightKafkaConfig` and `PortfolioKafkaConfig` inject `KafkaProperties` directly, causing `NoSuchBeanDefinitionException` at test startup.
   - **Fix**: Keep `KafkaAutoConfiguration` active (preserving `KafkaProperties`) and instead set `spring.kafka.listener.auto-startup=false` in each test. This prevents listener containers from connecting to a real broker without removing the beans that custom config classes depend on.
   - **Files updated**: `MarketSummaryIntegrationTest`, `BetterAuthSchemaExplorationTest`, `FlywayPreservationTest`, `PortfolioAnalyticsIntegrationTest`, `PortfolioHoldingsHydrationIT`, `PortfolioSeedServiceIT`.

### 7.5 Lambda Cold-Start Pre-Warm Strategy (`6128776`)

- **Problem**: The combined cold-start chain (api-gateway → portfolio-service) totals ~40 s. On a fresh deploy, the first seeding request from the Playwright global setup exceeded the downstream response budget and returned `504`.
- **Fix — CI Workflow Pre-Warm Step** (both `ci-verification.yml` and `synthetic-monitoring.yml`):
  - Added a **"Pre-warm AWS Lambda stack"** step that executes before Playwright runs.
  - Hits `/api/portfolio/health` through the gateway with `curl --max-time 65` (sufficient for a full cold start) and retries up to 5 times with a 5 s backoff.
  - Any non-`502`/`504` response is accepted as confirmation that both Lambdas are warm.
- **Fix — `seedFetch()` helper in `global-setup.ts`**:
  - Wraps every seeding `fetch()` call with `AbortSignal.timeout(70_000)` to cap Node's wait.
  - Detects `502`/`504` responses and retries up to **3 times** with a 5 s backoff, guarding against cases where market-data or insight-service remain cold after the pre-warm warms only api-gateway + portfolio-service.
- **Fix — `ci-verification.yml` aws-synthetic environment**:
  - Added `GATEWAY_BASE_URL` and `BASE_URL` pointing to the live production URL so `global-setup.ts` seeds the AWS environment rather than the local Docker Compose stack.
  - Added `SKIP_BACKEND_HEALTH_CHECK=true` to prevent health-poll timeouts against Lambda cold starts and CloudFront-protected actuator endpoints.

