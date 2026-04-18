# Deployment Verification Pipeline — 2026-04-15

## Summary

Introduced a full deployment verification pipeline that builds, tests, and validates container images end-to-end before they reach production. The pipeline covers multi-stage Docker builds with custom JREs, consumer-driven contract testing via Pact, hardened Playwright health-checks, a unified GitHub Actions CI workflow, and a local one-command verification script.

## Multi-Stage Dockerfiles (All 4 Java Services)

- **3-stage build**: Gradle + AOT → jdeps/jlink Custom JRE → Amazon Linux 2023 Minimal runtime
- **Runtime base**: switched from `alpine:3.20` to `public.ecr.aws/amazonlinux/amazonlinux:2023-minimal` to resolve glibc `posix_fallocate64` symbol incompatibility with Corretto-built JREs on musl
- **Lambda Web Adapter**: upgraded from `0.8.4` to `1.0.0` (`public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`)
- **RIE**: included in all images for local Lambda invoke testing
- **Build contexts**: all services use repo root (`.`) with explicit `dockerfile:` path, since Dockerfiles reference root-level files (`gradlew`, `settings.gradle`, `common-dto/`)

### Files Changed

- `portfolio-service/Dockerfile`
- `market-data-service/Dockerfile`
- `insight-service/Dockerfile`
- `api-gateway/Dockerfile`

## Pact Consumer-Driven Contract Testing

### Consumer Tests (Frontend)

- Installed `@pact-foundation/pact` v16.3.0 as devDependency
- Created dedicated Vitest config: `frontend/vitest.pact.config.ts`
- Added `test:pact` npm script
- Two consumer contract tests generating Pact Specification v4 files:
  - `frontend/tests/pact/portfolio-api.pact.spec.ts` — `GET /api/portfolio`
  - `frontend/tests/pact/insight-api.pact.spec.ts` — `GET /api/insights/market-summary`
- Generated Pact files committed to `frontend/pacts/`

### Provider Verification (Backend)

- Added `au.com.dius.pact.provider:spring7:4.7.0-beta.4` to portfolio-service and insight-service
- Created provider verification tests using `Spring7MockMvcTestTarget` (standalone MockMvc, no full app context):
  - `portfolio-service/src/test/java/com/wealth/portfolio/pact/PortfolioPactVerificationTest.java`
  - `insight-service/src/test/java/com/wealth/insight/pact/InsightPactVerificationTest.java`
- `@PactFolder("../frontend/pacts")` — relative path from each service's project directory

### Implementation Notes

- Actual package: `au.com.dius.pact.provider.spring.spring7` (not `au.com.dius.pact.provider.spring7`)
- Method: `setControllerAdvices()` (plural, not `setControllerAdvice`)
- `@PactFolder` path: `../frontend/pacts` (Gradle runs tests from the subproject directory)

## Playwright Health-Check Hardening

- Created `frontend/tests/e2e/global-setup.ts` — two-phase health-check poller:
  1. Deep: polls `GET /api/portfolio/health` through API Gateway (30s)
  2. Shallow fallback: polls `GET /actuator/health` for remaining timeout
  3. Configurable via `HEALTH_CHECK_TIMEOUT_MS` env var (default 120s)
- Added deep health endpoint to `PortfolioController`: `GET /api/portfolio/health` → `{"status":"UP","service":"portfolio-service"}`
- Wired into `playwright.config.ts` via `globalSetup` property

## GitHub Actions CI Pipeline

- Created `.github/workflows/ci-verification.yml` — unified 4-job pipeline:
  1. `unit-tests` — `./gradlew test` (JDK 25 Temurin + Gradle caching)
  2. `integration-tests` — `./gradlew integrationTest` (Testcontainers)
  3. `pact-consumer` — `npm run test:pact` (uploads Pact files as artifact)
  4. `docker-build-verify` — builds images (Docker layer cache `type=gha`), Pact provider verification, Docker Compose stack, Playwright E2E, pushes to GHCR on main
- Failure handling: uploads Playwright HTML report + container logs as artifacts
- GHCR push gated to `push` on `main` only (not PRs)
- Added supersession comments to existing `ci.yml` and `cd.yml`

## Docker Compose Updates

- **Entrypoint override**: all Java services use `entrypoint: ["/opt/java/bin/java", "-jar", "/app/app.jar"]` to bypass the Lambda Web Adapter locally (the adapter requires the Lambda Extensions API which only exists inside Lambda)
- **Health checks**: `curl -f` on service-specific endpoints (portfolio-service uses `/api/portfolio/health`, api-gateway uses `/actuator/health`, others use root path)
- **Resource limits**: `deploy.resources.limits.memory: 768M` (512M caused OOM during startup)
- **Dependency ordering**: `depends_on` with `condition: service_healthy` for cascading dependencies (insight-service → portfolio-service, api-gateway → all backends)
- **Build contexts fixed**: all services use repo root with explicit `dockerfile:` path

## Local Verification Script

- Created `scripts/verify.sh` — 7-stage sequential pipeline:
  1. Gradle bootJar (skippable with `--skip-build`)
  2. Docker Compose build
  3. Docker Compose up
  4. Health-check polling (120s timeout per service)
  5. Pact consumer tests
  6. Pact provider verification
  7. Playwright E2E
- Colour-coded output with pass/fail/skip summary table
- On failure: stops at failing stage, leaves containers running, prints cleanup command
- On success: offers interactive `docker compose down -v` cleanup

## Key Design Decisions Documented

| Decision                              | Rationale                                                                                                                           |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Amazon Linux 2023 Minimal over Alpine | Corretto JRE requires real glibc; Alpine's musl + libc6-compat shim missing `posix_fallocate64`                                     |
| Entrypoint override in Docker Compose | Lambda Web Adapter requires Lambda Extensions API; no passthrough mode exists outside Lambda                                        |
| Lambda Web Adapter 1.0.0              | Stable release with `AWS_LWA_` prefixed env vars and cleaner error handling                                                         |
| AOT disabled locally                  | `processAot` metadata not included in `bootJar` by default; AOT is a Lambda cold-start optimization                                 |
| Scoped Pact provider verification     | `--tests '*PactVerification*'` fails on modules without Pact tests; scoped to `:portfolio-service:test` and `:insight-service:test` |
