# Phase 4 — Azure Demo Readiness Phase 2: Observability, Synthetic Monitoring & CI Hardening
**Date:** 2026-05-13  
**Branch:** `feat/azure-demo-readiness-phase2`  
**Preceding changelog:** `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-10.md`

---

## Overview

This session completed the remaining items from the Phase 4 audit and addressed a cascade of CI failures discovered after the Phase 2 branch was pushed. Work split into four tracks:

1. **Observability parity** — `InfrastructureHealthLogger` extended to all four services under both `aws` and `azure` profiles, with a full unit and integration test suite.
2. **Azure synthetic monitoring** — new Playwright `azure-synthetic` project with five spec files, wired into `ci-verification.yml` (main push) and `synthetic-monitoring.yml` (hourly schedule).
3. **Workflow hardening** — legacy workflow disablements, deploy dispatcher fix, dead step removal, and Azure synthetic step placement.
4. **CI test fixes** — three cascading test failures in the `unit-tests` CI job fixed after the branch was pushed.

---

## Track 1 — Observability Parity (Tasks 7.1–7.3)

### `InfrastructureHealthLogger` extended to all services (`6618f24`)

**Problem:** `InfrastructureHealthLogger` existed only in `api-gateway` under `@Profile("aws")`. The other three services had no startup connectivity probe, and none of the four services logged under the `azure` profile.

**Fix:** Updated `@Profile` annotation from `"aws"` to `{"aws", "azure"}` in all four services:
- `api-gateway/src/main/java/com/wealth/gateway/InfrastructureHealthLogger.java`
- `portfolio-service/src/main/java/com/wealth/portfolio/InfrastructureHealthLogger.java`
- `market-data-service/src/main/java/com/wealth/market/InfrastructureHealthLogger.java`
- `insight-service/src/main/java/com/wealth/insight/InfrastructureHealthLogger.java`

Each service probes its own infrastructure dependencies on `ApplicationReadyEvent`:

| Service | Dependencies probed |
|---------|-------------------|
| api-gateway | Redis (PING) |
| portfolio-service | PostgreSQL (`SELECT 1`), Kafka (`describeTopics`), Redis (PING) |
| market-data-service | MongoDB (`{ ping: 1 }`), Kafka (`describeTopics`) |
| insight-service | Redis (PING), Kafka (`describeTopics`) |

Log prefixes: `[INFRA-OK]` on success, `[INFRA-FAIL]` with exception class and message on failure. Fires after startup so it never blocks readiness.

### Unit and integration tests for `InfrastructureHealthLogger` (`7966afa`)

Added 8 test files (2 per service: unit test + profile activation test), 45 test methods total:

**Unit tests** (`InfrastructureHealthLoggerTest`) — `@ExtendWith(MockitoExtension.class)`, no Spring context:
- All dependencies healthy → `[INFRA-OK]` logs for each
- Each dependency failing independently → `[INFRA-FAIL]` with correct exception class and message
- Startup banner logged at start and end
- Service name present in banner

**Profile activation tests** (`InfrastructureHealthLoggerProfileTest`) — `@Tag("integration")`, full `@SpringBootTest` with Testcontainers:
- `@ActiveProfiles("local")` → bean **not** registered (`NoSuchBeanDefinitionException`)
- `@ActiveProfiles("aws")` → bean **is** registered
- `@ActiveProfiles("azure")` → bean **is** registered

Testcontainers setup per service:

| Service | Container | `webEnvironment` |
|---------|-----------|-----------------|
| api-gateway | `redis:7-alpine` | `RANDOM_PORT` (required by Spring Cloud Gateway) |
| portfolio-service | `postgres:16-alpine` | `NONE` |
| market-data-service | `mongo:7` | `NONE` |
| insight-service | `redis:7-alpine` | `NONE` |

All containers are started once per JVM via a static initialiser and shared across the three nested profile test classes to avoid redundant container startups.

---

## Track 2 — Azure Synthetic Monitoring (Tasks 1.1–1.4, 3.1)

### Playwright `azure-synthetic` project (`86af3d2`)

Added `azure-synthetic` project to `frontend/playwright.config.ts`:
- Base URL: `https://vibhanshu-ai-portfolio.dev`
- API base URL: `https://api.vibhanshu-ai-portfolio.dev`
- Timeout: 60 s (accounts for ACA cold-start / scale-from-zero)
- Browser: Chromium only (excluded from the default `chromium` project to avoid double-running)

### Five synthetic spec files (`a3f1441`)

`frontend/tests/e2e/azure-synthetic/`:

| File | Purpose |
|------|---------|
| `azure-synthetic.spec.ts` | Full login → dashboard health check |
| `login.spec.ts` | Standalone login verification |
| `api-live-smoke.spec.ts` | API smoke tests (health, auth, portfolio, market data) |
| `live-contract.spec.ts` | 160-asset portfolio contract verification |
| `ai-insights.spec.ts` | Azure OpenAI integration (chat response, market summary grid) |
| `README.md` | Setup, env vars, run commands |

### Synthetic monitoring wired into scheduled workflow (`7db853e`)

`synthetic-monitoring.yml` extended with an `azure-synthetic` job that runs `api-live-smoke.spec.ts` and `azure-synthetic.spec.ts` on the hourly cron schedule. Runs only when `vars.CLOUD_PROVIDER == 'azure'`.

### Azure synthetic step added to `ci-verification.yml` (`0df0753`)

Added `Run Azure synthetic monitoring` step to the `docker-build-verify` job in `ci-verification.yml`. Runs `api-live-smoke.spec.ts` and `azure-synthetic.spec.ts` under `--project=azure-synthetic` on every push to `main` when `vars.CLOUD_PROVIDER == 'azure'`.

---

## Track 3 — Workflow Hardening

### Legacy workflow disablements (`37caa67`, `e6b720c`)

Disabled four legacy workflows that were still firing on push/PR triggers, causing confusion in the Actions tab:

| Workflow | Change |
|----------|--------|
| `deploy-aws.yml` | Redirects to `deploy-azure.yml`; `workflow_dispatch` only |
| `deploy.yml` | Redirects to `deploy-azure.yml`; `workflow_dispatch` only |
| `terraform.yml` | Redirects to `terraform-azure.yml`; `workflow_dispatch` only |
| `frontend-cd.yml` | Redirects to `deploy-azure.yml`; `workflow_dispatch` only |
| `ci.yml` | Removed push+PR triggers; added `workflow_dispatch` with required `reason` input; added `DISABLED` comment block |
| `frontend-e2e-integration.yml` | Removed push trigger; added `workflow_dispatch` |
| `frontend-cd.yml` | Added required `reason` input to existing `workflow_dispatch` |

### Deploy dispatcher fix (`b417203`)

`deploy-aws.yml` had only `workflow_dispatch` after disabling, which broke `deploy.yml`'s `uses: ./.github/workflows/deploy-aws.yml` call (reusable workflows require `workflow_call`). Added `workflow_call` back to `deploy-aws.yml`.

### Dead FQDN resolution step removed from `deploy-azure.yml` (`8968444`)

The `Resolve API Gateway FQDN` step in the `deploy-frontend` job was a no-op — `NEXT_PUBLIC_API_BASE_URL` is hardcoded to `https://api.vibhanshu-ai-portfolio.dev` since the DNS cutover. Removed the step to reduce noise.

### Synthetic monitoring job placement corrected (`8cc2d63`)

A synthetic monitoring job had been added directly to `deploy-azure.yml` (Finding 9). This was out of scope — synthetic monitoring belongs in `synthetic-monitoring.yml` (hourly schedule) and `ci-verification.yml` (main push). Removed the job from `deploy-azure.yml` and added a source-of-truth comment pointing to the correct locations.

### AWS/Azure synthetic monitoring job fixes (`e41c747`)

`synthetic-monitoring.yml` had the AWS job pointing to the wrong domain and the Azure job using a stale email. Fixed both to use the correct canonical domains and env vars.

---

## Track 4 — CI Test Fixes (Post-Push)

Three cascading failures in the `unit-tests` CI job were discovered and fixed after the branch was pushed. Each was a different root cause.

### Fix 1 — api-gateway: JWT secret missing for `aws`/`azure` profile tests (`c452053`)

**Root cause:** `InfrastructureHealthLoggerProfileTest$AwsProfileTest` and `AzureProfileTest` in api-gateway loaded a full `@SpringBootTest` context. `JwtDecoderConfig.hmacJwtDecoder()` throws `IllegalStateException` when `auth.jwt.secret` is shorter than 32 bytes. `AUTH_JWT_SECRET` is not set in CI, so the property resolved to the empty-string default.

The `local` profile test already passed because `src/test/resources/application-local.yml` supplied a valid test secret. The `aws` and `azure` profile tests had no equivalent.

**Fix:** Added `src/test/resources/application-aws.yml` and `src/test/resources/application-azure.yml` to api-gateway with the same 32-char test secret. Spring Boot picks up test-classpath ymls before main-classpath ones, so production configs are unaffected.

*(Note: these files became redundant once the profile tests were moved to `@Tag("integration")` in the subsequent fix, but are retained as a safety net.)*

### Fix 2 — All services: `KafkaAdmin.describeTopics()` is no longer void (`867d8d6`)

**Root cause:** In Spring Kafka 4.x (shipped with Spring Boot 4), `KafkaAdmin.describeTopics(String... topicNames)` changed from `void` to returning `Map<String, TopicDescription>`. The unit tests used `doNothing().when(kafkaAdmin).describeTopics(...)`, which Mockito rejects with `"Only void methods can doNothing()"`.

**Affected tests:** `InfrastructureHealthLoggerTest` in all three services that use Kafka (insight, portfolio, market-data) — 6 failing tests per service.

**Fix:** Replaced all `doNothing().when(kafkaAdmin).describeTopics(...)` with `when(kafkaAdmin.describeTopics(...)).thenReturn(Map.of())`. Added `import java.util.Map` to each test file.

Also added `.gitleaks.toml` to allowlist `src/test/resources/*.yml` files, which contain hardcoded non-production test secrets that were triggering the Gitleaks scan.

### Fix 3 — All services: `InfrastructureHealthLoggerProfileTest` missing `@Tag("integration")` (`4b240c8`)

**Root cause:** The four `InfrastructureHealthLoggerProfileTest` classes used `@SpringBootTest @ActiveProfiles(...)` without `@Tag("integration")`. This placed them in the fast unit-test lane (`./gradlew test`), where no infrastructure is available. Context startup failed:

| Service | Failure |
|---------|---------|
| market-data-service | `MongoTimeoutException` — no MongoDB on `localhost:27017` |
| portfolio-service | `FlywaySqlUnableToConnect` — no Postgres on `localhost:5432` |
| insight-service | Redis connection failure |
| api-gateway | JWT secret missing (fixed by Fix 1 above, but would have failed on Redis next) |

The main-branch convention is that every `@SpringBootTest` with real Spring Data connectivity carries `@Tag("integration")` and runs under the `integrationTest` task with Testcontainers (see `LocalMarketDataSeederIntegrationTest`, `FlywayPreservationTest`, `MarketSummaryIntegrationTest`, `RateLimitingIntegrationTest`).

**Fix:** Rewrote all four `InfrastructureHealthLoggerProfileTest` classes to match the main-branch convention:
- Added `@Tag("integration")` to each nested static class
- Added `@Testcontainers` with the service's standard container
- Wired container coordinates via `@DynamicPropertySource`
- Disabled Kafka listener auto-startup (`spring.kafka.listener.auto-startup=false`) to avoid broker connection attempts
- api-gateway uses `webEnvironment = RANDOM_PORT` (Spring Cloud Gateway's `httpClientSslConfigurer` requires `ServerProperties`, which is not available under `NONE`)
- insight-service disables AI ChatModel auto-config (`spring.ai.model.chat=none`) to avoid requiring Bedrock/Azure OpenAI credentials

---

## Infrastructure Fixes

### ACA `target_port` aligned to 8080 (`92f9432`)

**Problem:** Terraform was configuring `target_port=8081/8082/8083` for portfolio/market-data/insight-service, matching the local Docker Compose convention from `application.yml`. But `application-prod.yml` (active on both AWS and Azure) hardcodes `server.port=8080` across all services. ACA logged `TargetPort 8081 does not match the listening port 8080` and the startup probe failed on every new revision.

**Fix:** Aligned `target_port` to `8080` for all three internal Container Apps in the Terraform module.

### Redis truststore fixed for Upstash (`9b1855c`)

**Problem:** `api-gateway` and `insight-service` were loading a JKS truststore that was specific to Aiven's Redis. After switching to Upstash, the JKS was no longer valid and caused TLS handshake failures.

**Fix:** Removed the Aiven-specific JKS truststore loading from `ApiGatewayApplication.java` and `InsightApplication.java`. Upstash uses a publicly trusted CA, so no custom truststore is needed. Updated the corresponding truststore wiring tests.

### Kafka topic auto-creation for Aiven free tier (`178ac18`, `3156483`)

**Problem:** Aiven's free-tier Kafka does not auto-create topics. `market-prices` was missing on first deploy, causing `market-data-service` to fail on publish and `portfolio-service`/`insight-service` to fail on consume.

**Fix:** Added `KafkaTopicConfig.java` to `market-data-service` that declares a `NewTopic` bean for `market-prices`. Spring Kafka's `KafkaAdmin` creates the topic on startup if it does not exist. Default partition count set to `1` (Aiven free tier limit) via `application-prod-kafka.yml`.

### Login page infrastructure notice removed (`eb5b8f7`)

Removed the infrastructure notice banner from `frontend/src/app/(auth)/login/page.tsx`. The banner was a temporary placeholder added during initial development; the live Azure deployment makes it obsolete.

### Terraform import blocks removed (`6c0f9ad`)

The four Container App `import {}` blocks and four AcrPull role assignment `import {}` blocks added during the live deployment debugging session (Phase 4, `0466325`) are now no-ops — all resources are in Terraform state. Removed to keep `main.tf` clean.

---

## E2E Spec Fixes

A series of iterative fixes to the `azure-synthetic` specs after initial implementation:

| Commit | Fix |
|--------|-----|
| `3bfee98` | Corrected `azure-synthetic` Playwright project config — domain, timeout, chromium exclusion |
| `ea25f30` | Added required API assertions to `api-live-smoke.spec.ts` (Phase 2 acceptance criteria) |
| `383d306` | Fixed market-data seed body, response assertions, and stale domain references |
| `d0105df` | Corrected `/ai-insights` route (was `/insights`); removed stale `.azurewebsites.net` references; removed hardcoded password fallback |
| `22e4693` | Rewrote `ai-insights.spec.ts` to use real `data-testid` attributes from `ChatInterface.tsx` and `MarketSummaryGrid.tsx` |
| `1504a41` | Derived display name from env in `login.spec.ts`; fixed README `ai-insights` entry |
| `840cd0d` | Assert email (not display name) in `login.spec.ts` — `E2E_TEST_USER_NAME` is not forwarded to `synthetic-monitoring.yml` |

---

## Commit Log

| Commit | Summary |
|--------|---------|
| `92f9432` | fix(azure): align ACA target_port to 8080 for all services |
| `9b1855c` | fix(tls): stop using Aiven-only JKS as Redis truststore for Upstash |
| `178ac18` | feat(kafka): auto-create market-prices topic on startup |
| `3156483` | fix(kafka): set default partition count to 1 for Aiven free tier |
| `eb5b8f7` | fix(ui): remove infrastructure notice banner from login page |
| `a3f1441` | feat(e2e): implement Azure synthetic monitoring suite (Tasks 1.1–1.3) |
| `37caa67` | chore(workflows): disable legacy workflows for Azure-first strategy (Tasks 5.1–5.4) |
| `6618f24` | feat(observability): enable InfrastructureHealthLogger for both AWS and Azure (Tasks 7.1–7.2) |
| `6c0f9ad` | chore(terraform): remove import blocks after successful state import (Task 8.1) |
| `86af3d2` | feat(e2e): add azure-synthetic project to Playwright configuration (Task 1.4) |
| `8968444` | refactor(workflows): remove dead FQDN resolution step from deploy-azure (Task 4.1) |
| `7966afa` | test(observability): add comprehensive unit tests for InfrastructureHealthLogger (Task 7.3) |
| `7db853e` | feat(workflows): add Azure synthetic monitoring to scheduled workflow (Task 3.1) |
| `b417203` | fix(workflows): restore workflow_call to deploy-aws.yml so deploy.yml dispatcher routes correctly |
| `3bfee98` | fix(e2e): correct azure-synthetic Playwright project — domain, timeout, chromium exclusion |
| `ea25f30` | fix(e2e): add required API assertions to Azure smoke spec (Finding 3, blocking) |
| `0df0753` | fix(workflows): add Azure synthetic step to ci-verification.yml on main push (Finding 4, blocking) |
| `e6b720c` | fix(workflows): complete legacy workflow disablements with required reason inputs (Finding 6) |
| `e41c747` | fix(workflows): revert AWS job to original state; fix Azure job domain/email (Findings 7+8) |
| `8cc2d63` | fix(workflows): remove out-of-scope synthetic-monitoring job from deploy-azure (Findings 9+10) |
| `383d306` | fix(e2e): market-data seed body, response assertions, and stale domain cleanup |
| `d0105df` | fix(e2e): correct /ai-insights route and remove all stale .azurewebsites.net references |
| `22e4693` | fix(e2e): rewrite ai-insights smoke to use real data-testid attributes only |
| `1504a41` | fix(e2e): derive display name from env in login.spec.ts; fix README ai-insights entry |
| `840cd0d` | fix(e2e): assert email not display name in login.spec.ts |
| `c452053` | fix(api-gateway): supply test JWT secret for aws/azure profile tests |
| `867d8d6` | fix(tests): adapt KafkaAdmin.describeTopics stubs for Spring Kafka 4 |
| `4b240c8` | test(observability): tag InfrastructureHealthLoggerProfileTest as integration |

---

## Known Remaining Items

- **`application-aws.yml` / `application-azure.yml` in api-gateway test resources** — added as a JWT secret safety net for the `aws`/`azure` profile tests. Now redundant since those tests run under `@Tag("integration")` with `@DynamicPropertySource`. Can be removed in a follow-up cleanup.
- **Kafka listener noise in integration tests** — portfolio-service and insight-service integration tests log Kafka reconnect warnings during context shutdown. Harmless but noisy; can be suppressed with a test-scoped `application.yml` that sets `reconnect.backoff.max.ms` to a lower value.
- **`CLOUD_PROVIDER` repo variable** — must be set to `azure` for Azure synthetic monitoring steps in `ci-verification.yml` and `synthetic-monitoring.yml` to fire. Not documented in the runbook.
