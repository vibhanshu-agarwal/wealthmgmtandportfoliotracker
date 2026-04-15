# Docker Healthcheck Fix & Auth Bugfix Spec — 2026-04-15

Continues from [v1](CHANGES_INFRA_SUMMARY_2026-04-15_v1.md), which introduced the deployment verification pipeline, multi-stage Dockerfiles, and Pact contract testing.

## Summary

Fixed the Docker Compose health check failures that prevented `market-data-service`, `insight-service`, and `api-gateway` from starting. Also brought `portfolio-service` into scope after discovering the same root cause. Created a bugfix spec for the separate Better Auth sign-in 500 error (implementation deferred to a follow-up).

## Docker Healthcheck Fix

### Problem

The CI/CD pipeline (`e2e-full-stack#95`) failed because `market-data-service` and `insight-service` containers were marked unhealthy. Two root causes:

1. **Missing dependency**: Neither service had `spring-boot-starter-actuator`, so `/actuator/health` did not exist
2. **Wrong health check URL**: `docker-compose.yml` health checks targeted `/` (root path), which returned 404

This cascaded: `insight-service` and `api-gateway` couldn't start because their `condition: service_healthy` dependencies were unhealthy. The Dockerfiles already set `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`, so the Lambda deployment path was also broken.

During testing, `portfolio-service` was found to have the same issue — it had a custom `/api/portfolio/health` controller endpoint that masked the problem locally, but its Dockerfile also referenced `/actuator/health` without the actuator dependency.

### Fix

Added `spring-boot-starter-actuator` to all three backend services and standardized Docker Compose health checks to use `/actuator/health/readiness` (the Kubernetes-style readiness probe). The readiness endpoint avoids false negatives from infrastructure health indicators (e.g., Redis, Kafka) that may report DOWN during startup even though the app is ready to serve traffic.

### Files Changed

| File                               | Change                                              |
| ---------------------------------- | --------------------------------------------------- |
| `market-data-service/build.gradle` | Added `spring-boot-starter-actuator`                |
| `insight-service/build.gradle`     | Added `spring-boot-starter-actuator`                |
| `portfolio-service/build.gradle`   | Added `spring-boot-starter-actuator`                |
| `docker-compose.yml`               | All 4 services now use `/actuator/health/readiness` |

### Files NOT Changed (Confirmed)

- All Dockerfiles — already had `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`
- All `application.yml` files — Spring Boot auto-configures actuator
- `api-gateway/build.gradle` — already had actuator

### Verification

- `scripts/verify-healthcheck-bug.sh` — 6 checks confirming actuator dependency present and health check URLs correct
- `scripts/verify-healthcheck-preservation.sh` — 13 checks confirming Dockerfiles, dependency chain, and unaffected configs preserved
- Gradle unit tests pass for all three services with the new dependency
- Full `docker compose up` verified: all 4 services reach `(healthy)` status

### Why `/actuator/health/readiness` Instead of `/actuator/health`

The composite `/actuator/health` endpoint aggregates all auto-configured health indicators (db, redis, kafka, diskSpace). In `portfolio-service`, one indicator reported DOWN during startup, causing a 503 even though the app was functional. The `/actuator/health/readiness` endpoint checks only the readiness group, which correctly reports UP when the app is ready to serve traffic.

## Better Auth Sign-In Bugfix Spec (Deferred)

### Problem

`POST /api/auth/sign-in/email` returns HTTP 500. The `ba_*` tables (`ba_user`, `ba_session`, `ba_account`, `ba_verification`) don't exist in PostgreSQL. The schema SQL (`frontend/scripts/better-auth-schema.sql`) and seed script (`frontend/scripts/seed-dev-user.ts`) exist but are never executed during startup.

### Spec Created

- `.kiro/specs/better-auth-signin-fix/bugfix.md` — Requirements document (5 defect clauses, 5 expected behavior, 5 preservation)
- `.kiro/specs/better-auth-signin-fix/design.md` — Design document with fix approach: add Flyway V8 migration for `ba_*` tables and V9 migration for dev user seed

### Implementation Deferred

The fix (creating Flyway migrations) will be implemented in a follow-up session.

## Key Decisions

| Decision                                                 | Rationale                                                                                                                                  |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Use `/actuator/health/readiness` over `/actuator/health` | Composite health aggregates all indicators; readiness checks only app-level readiness, avoiding false negatives from slow infrastructure   |
| Add actuator to `portfolio-service` too                  | Same root cause — Dockerfile referenced `/actuator/health` but dependency was missing; custom controller endpoint masked the issue locally |
| Flyway migration for `ba_*` tables (planned)             | Integrates with existing migration system, runs automatically on portfolio-service startup, tracked by Flyway schema history               |
| Keep existing `frontend/scripts/better-auth-schema.sql`  | Preserved as documentation/reference alongside the Flyway migration                                                                        |
