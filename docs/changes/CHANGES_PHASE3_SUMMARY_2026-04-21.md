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

