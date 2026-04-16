# Implementation Plan: Deployment Verification Pipeline

## Overview

This plan modernizes the build, packaging, and verification pipeline. Tasks are ordered to deliver the multi-stage Dockerfile for portfolio-service first, then extend to remaining services, add Pact contract testing, harden Playwright health-checks, refactor CI/CD, update Docker Compose, and wire everything together with a local verification script.

All Java services use Gradle (Groovy DSL), Spring Boot 4.0.5, Java 25. Pact provider tests use `au.com.dius.pact.provider:spring7:4.7.0-beta.4` with `Spring7MockMvcTestTarget`.

## Tasks

- [x] 1. Multi-Stage Dockerfile for portfolio-service
  - [x] 1.1 Create the multi-stage Dockerfile at `portfolio-service/Dockerfile`
    - Replace the existing single-stage Dockerfile with a 3-stage build:
      - **Stage 1 (builder):** `FROM amazoncorretto:25` — copy Gradle wrapper + source, run `./gradlew :portfolio-service:bootJar -Dspring.aot.enabled=true`
      - **Stage 2 (jre-builder):** `FROM amazoncorretto:25` — extract fat JAR libs, run `jdeps --ignore-missing-deps --print-module-deps` on all JARs, pipe to `jlink --add-modules` (with fallback modules `jdk.unsupported,java.security.jgss`), produce Custom JRE
      - **Stage 3 (runtime):** `FROM alpine:3.20` — install glibc compat, copy Custom JRE, copy app.jar, copy AWS Lambda Web Adapter from `public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`, copy RIE binary
    - Set `ENTRYPOINT ["/opt/extensions/aws-lambda-web-adapter"]` and `CMD ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]`
    - Configure `AWS_LWA_PORT=8081` and `AWS_LWA_READINESS_CHECK_PATH=/actuator/health`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9_

  - [x] 1.2 Smoke-test the portfolio-service Docker image
    - Build the image: `docker build -t portfolio-service:test ./portfolio-service`
    - Verify Custom JRE exists, Lambda Web Adapter binary at `/opt/extensions/aws-lambda-web-adapter`, RIE at `/usr/local/bin/aws-lambda-rie`
    - Verify no full JDK present and image size < 300 MB
    - Verify `docker run -p 8081:8081 portfolio-service:test` serves HTTP on port 8081
    - _Requirements: 1.4, 1.5, 1.6, 1.8_

- [x] 2. Multi-Stage Dockerfiles for remaining Java services
  - [x] 2.1 Create multi-stage Dockerfile for `market-data-service/Dockerfile`
    - Same 3-stage pattern as portfolio-service; `AWS_LWA_PORT=8082`
    - Build arg or Gradle command targets `:market-data-service:bootJar`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 2.2 Create multi-stage Dockerfile for `insight-service/Dockerfile`
    - Same 3-stage pattern; `AWS_LWA_PORT=8083`
    - Build arg or Gradle command targets `:insight-service:bootJar`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 2.3 Create multi-stage Dockerfile for `api-gateway/Dockerfile`
    - Same 3-stage pattern; `AWS_LWA_PORT=8080`
    - Build arg or Gradle command targets `:api-gateway:bootJar`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

- [x] 3. Checkpoint — Verify all Dockerfiles build
  - Ensure all four services build successfully with `docker build`
  - Ensure all tests pass (`./gradlew test`), ask the user if questions arise.

- [x] 4. Pact consumer contract tests (frontend)
  - [x] 4.1 Install `@pact-foundation/pact` as a devDependency in `frontend/`
    - Add `@pact-foundation/pact` v13.x to `frontend/package.json` devDependencies
    - Add `"test:pact": "vitest run tests/pact/"` script to `frontend/package.json`
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 4.2 Create consumer contract test for portfolio API
    - Create `frontend/tests/pact/portfolio-api.pact.spec.ts`
    - Use `PactV4` with consumer `wealth-frontend`, provider `portfolio-service`, dir `./pacts`
    - Define interaction for `GET /api/portfolio` — expected response schema with `holdings[]` (symbol, quantity, currentPrice, totalValue) and `totalPortfolioValue`
    - Use `MatchersV3.like()` for flexible matching
    - _Requirements: 3.1, 3.3, 3.5_

  - [x] 4.3 Create consumer contract test for insight API
    - Create `frontend/tests/pact/insight-api.pact.spec.ts`
    - Use `PactV4` with consumer `wealth-frontend`, provider `insight-service`, dir `./pacts`
    - Define interaction for `GET /api/insights/market-summary` — expected response schema
    - _Requirements: 3.2, 3.3, 3.5_

  - [x] 4.4 Run consumer tests and verify Pact file generation
    - Execute `npm run test:pact` from `frontend/`
    - Verify Pact JSON files are generated in `frontend/pacts/`
    - Verify files conform to Pact Specification v4
    - _Requirements: 3.3, 3.4_

- [x] 5. Pact provider verification (backend)
  - [x] 5.1 Add Pact provider dependency to portfolio-service
    - Add `testImplementation 'au.com.dius.pact.provider:spring7:4.7.0-beta.4'` to `portfolio-service/build.gradle`
    - _Requirements: 4.1_

  - [x] 5.2 Create Pact provider verification test for portfolio-service
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/pact/PortfolioPactVerificationTest.java`
    - Use `@WebMvcTest` + `@Provider("portfolio-service")` + `@PactFolder("../../frontend/pacts")`
    - Configure `Spring7MockMvcTestTarget` (NOT Spring6MockMvcTestTarget)
    - Implement `@State` methods to seed test data (portfolio holdings)
    - Use `@TestTemplate` + `@ExtendWith(PactVerificationInvocationContextProvider.class)`
    - _Requirements: 4.1, 4.3, 4.4_

  - [x] 5.3 Add Pact provider dependency to insight-service
    - Add `testImplementation 'au.com.dius.pact.provider:spring7:4.7.0-beta.4'` to `insight-service/build.gradle`
    - _Requirements: 4.2_

  - [x] 5.4 Create Pact provider verification test for insight-service
    - Create `insight-service/src/test/java/com/wealth/insight/pact/InsightPactVerificationTest.java`
    - Use `@WebMvcTest` + `@Provider("insight-service")` + `@PactFolder("../../frontend/pacts")`
    - Configure `Spring7MockMvcTestTarget`
    - Implement `@State` methods to seed test data (cached market summary)
    - _Requirements: 4.2, 4.3, 4.4_

- [x] 6. Checkpoint — Verify Pact tests pass
  - Ensure consumer tests generate Pact files in `frontend/pacts/`
  - Ensure provider verification tests pass for both portfolio-service and insight-service
  - Ensure all existing tests still pass (`./gradlew test`), ask the user if questions arise.

- [x] 7. Playwright health-check hardening
  - [x] 7.1 Create the `globalSetup` health-check script
    - Create `frontend/tests/e2e/global-setup.ts`
    - Implement polling logic:
      1. Poll `GET http://localhost:8080/api/portfolio/health` (deep health-check through API Gateway → portfolio-service) every 2 seconds
      2. After 30s of deep-check failures, fall back to `GET http://localhost:8080/actuator/health` (shallow)
      3. Log each attempt with timestamp and HTTP status
      4. Abort with clear timeout error after configurable timeout (default 120s), including last observed HTTP status
    - _Requirements: 5.1, 5.2, 5.3, 5.5_

  - [x] 7.2 Add a deep health-check endpoint to portfolio-service
    - Add a `/health` endpoint to `PortfolioController` (or a dedicated `HealthController`) that returns `{ "status": "UP", "service": "portfolio-service" }`
    - Ensure the API Gateway routes `/api/portfolio/health` to this endpoint
    - _Requirements: 5.1_

  - [x] 7.3 Wire `globalSetup` into `playwright.config.ts`
    - Add `globalSetup: './tests/e2e/global-setup.ts'` to the Playwright config
    - Ensure the `globalSetup` runs before the `setup` project (auth setup)
    - _Requirements: 5.4_

  - [x] 7.4 Test health-check fallback behavior
    - Verify that when the deep endpoint returns non-200, the poller falls back to `/actuator/health`
    - Verify that when all endpoints fail, the poller aborts with a descriptive timeout error
    - _Requirements: 5.2, 5.3_

- [x] 8. Refactored GitHub Actions CI pipeline
  - [x] 8.1 Create the unified CI verification workflow
    - Create `.github/workflows/ci-verification.yml`
    - Define jobs in order:
      1. `unit-tests` — `./gradlew test --no-daemon` (JDK 25, Temurin, Gradle cache)
      2. `integration-tests` — `./gradlew integrationTest --no-daemon` (needs `unit-tests`)
      3. `pact-consumer` — `cd frontend && npm ci && npm run test:pact` (needs `integration-tests`)
      4. `docker-build-verify` — builds all 4 service images with `docker build`, starts Docker Compose stack, runs Pact provider verification against running services, runs Playwright E2E, pushes verified images to GHCR on success
    - On failure: upload Playwright HTML report as artifact, dump container logs via `docker compose logs`
    - Cache: Gradle dependencies (`~/.gradle/caches`), Docker layer cache (`type=gha`), npm cache
    - Trigger on: `push` to `main`/`architecture/**`, `pull_request` to `main`/`architecture/**`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

  - [x] 8.2 Update existing CI/CD workflows
    - Review `.github/workflows/ci.yml` and `.github/workflows/cd.yml` — either integrate into the new unified workflow or add comments noting the new `ci-verification.yml` supersedes them for verification
    - Ensure no duplicate triggers or conflicting jobs
    - _Requirements: 6.1, 6.7_

- [x] 9. Checkpoint — Verify CI pipeline configuration
  - Review the workflow YAML for correctness (job dependencies, caching, secrets, permissions)
  - Ensure all tests pass locally, ask the user if questions arise.

- [x] 10. Docker Compose updates
  - [x] 10.1 Update `docker-compose.yml` with health checks and resource limits
    - Add `entrypoint` override on all Java services to bypass Lambda Web Adapter for local development: `["/opt/java/bin/java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]`
    - Fix build contexts: set to repo root (`.`) with explicit `dockerfile:` path for market-data-service, insight-service, and api-gateway (their Dockerfiles reference root-level files)
    - Add `healthcheck` using `curl -f` to all four Java services (curl is installed in the Alpine runtime stage)
    - Add `deploy.resources.limits.memory: 768M` to each Java service (512M caused OOM)
    - Update `depends_on` to use `condition: service_healthy` for insight-service → portfolio-service and api-gateway → all three backend services
    - Preserve all existing port mappings (8080, 8081, 8082, 8083) and environment variables
    - Upgrade Lambda Web Adapter from 0.8.4 → 1.0.0 in all four Dockerfiles
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 10.2 Verify Docker Compose stack starts correctly
    - Run `docker compose up -d` and confirm all services reach healthy state
    - Verify all ports are accessible and services respond on expected endpoints
    - _Requirements: 7.4, 7.5_

- [x] 11. Local verification script
  - [x] 11.1 Create `scripts/verify.sh`
    - Implement the following stages in sequence:
      1. `./gradlew bootJar -Dspring.aot.enabled=true` — build all service JARs with AOT
      2. `docker compose build` — build all container images
      3. `docker compose up -d` — start the full stack
      4. Wait for all health checks to pass (poll `/actuator/health` for each service)
      5. `cd frontend && npm run test:pact` — run Pact consumer contract tests
      6. `./gradlew test --tests '*PactVerification*'` — run Pact provider verification
      7. `cd frontend && npx playwright test` — run Playwright E2E suite
      8. Print verification summary (pass/fail per stage)
      9. On failure: stop at failing stage, print failure details, leave containers running for debugging, print `docker compose down -v` as cleanup command
      10. On success: offer `docker compose down -v` cleanup
    - Make the script executable (`chmod +x`)
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [x] 12. Final checkpoint — Full pipeline verification
  - Ensure all tests pass (`./gradlew test`, `npm run test:pact`, Pact provider verification)
  - Ensure Docker Compose stack starts with health checks
  - Ensure `scripts/verify.sh` runs end-to-end successfully
  - Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The Dockerfile pattern is identical across all four Java services — portfolio-service is implemented first as the reference, then replicated
- Pact provider tests use `au.com.dius.pact.provider:spring7:4.7.0-beta.4` with `Spring7MockMvcTestTarget` (Spring Boot 4 / Spring 7 compatible)
- The `AWS_LWA_PORT` must match each service's actual server port (8080 for api-gateway, 8081 for portfolio-service, 8082 for market-data-service, 8083 for insight-service)
- **Entrypoint Override (Local):** Docker Compose overrides the Dockerfile ENTRYPOINT to run `java -jar` directly, bypassing the Lambda Web Adapter. The adapter requires the Lambda Extensions API which only exists inside Lambda — outside Lambda it exits immediately. The Dockerfile ENTRYPOINT remains the adapter for Lambda deployments. CI smoke tests will validate the adapter-to-app bridge via RIE in a future phase.
- **Lambda Web Adapter version:** 1.0.0 (`public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0`) — upgraded from 0.8.4 for better error handling and `AWS_LWA_` prefixed env var support
- **Memory limits:** 768M per Java service (512M caused OOM during AOT hydration on startup)
- **Build contexts:** All four service Dockerfiles use the repo root (`.`) as build context with explicit `dockerfile:` path, since they reference root-level files (`gradlew`, `settings.gradle`, `common-dto/`)
