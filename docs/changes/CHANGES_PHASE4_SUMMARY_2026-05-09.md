# Phase 4 — Azure Container Apps Deployment
**Date:** 2026-05-09  
**Branch:** `feat/phase4-azure-migration`  
**Spec:** `.kiro/specs/azure-container-apps-deployment/`

---

## Overview

This phase adds a complete Azure Container Apps (ACA) deployment path alongside the existing AWS Lambda path. All changes are **additive** — no existing AWS Dockerfiles, CDK stacks, AWS Terraform modules, existing workflows, or Spring profile YAML files (`application.yml`, `application-prod.yml`, `application-local.yml`, `application-aws.yml`) were modified beyond the explicitly authorised exceptions listed below.

The implementation covers: JDK 21 toolchain alignment, four `@Profile` widenings, two new Azure OpenAI adapters with a P1 mutual-exclusion validator, five new Spring YAML overlays, four new `Dockerfile.azure` files, Terraform relocation + a new Azure root with a reusable `container-app` module, two mandatory plan-assertion Python scripts, and two new GitHub Actions workflows.

---

## Authorised Changes

The following files were modified outside the Azure-only additions. All other modifications are new files only.

| File | Change | Requirement |
|------|--------|-------------|
| `build.gradle` | `JavaLanguageVersion.of(25)` → `JavaLanguageVersion.of(21)` | Req 0.1, 13.2 |
| `insight-service/src/main/resources/application-bedrock.yml` | Added `spring.ai.model.chat: bedrock-converse` under `spring.ai` (single key, no other changes) | Req 4.2, 13.4 |
| `insight-service/src/main/java/.../infrastructure/ai/AiConfig.java` | Added `@ConditionalOnBean(ChatModel.class)` to `chatClientBuilder` — required because both AI starters are now on the classpath; without this the mock profile (no `ChatModel`) fails with `NoUniqueBeanDefinitionException` | Req 3.6 (adapter uniqueness) |
| `insight-service/src/test/java/.../MarketSummaryIntegrationTest.java` | Added `spring.ai.model.chat=none` and a placeholder Azure endpoint to the test's `properties` array — prevents both AI auto-configurations from conflicting in the test context | Req 3.6 (test stability) |

Note on Req 0.2/0.3: these are build-success and bytecode constraints (not modification authorisations) — they are satisfied by the toolchain change but are not themselves the authorisation source.

---

## Changes by Area

### 1. Gradle Toolchain (Task 1)

- **`build.gradle`** — downgraded `JavaLanguageVersion` from 25 to 21. All four services and `common-dto` now compile and produce Java 21 bytecode (class-file major version 65).
- **`insight-service/.../BedrockAiInsightServicePropertyTest.java`** — removed stale `"Migrated from the removed OllamaAiInsightServicePropertyTest"` javadoc reference.

### 2. api-gateway Profile Widening — G1 (Task 2)

- **`JwtDecoderConfig.java`** — `@Profile({"local","aws"})` → `@Profile({"local","aws","azure"})` on `hmacJwtDecoder`; `IllegalStateException` message updated to reference `local/aws/azure`.
- **`PreservationPropertyTest.java`** — `jwtDecoderConfigHasCorrectProfileAnnotations` assertion updated to `containsExactlyInAnyOrder("local","aws","azure")`. Three new P2 test methods added (`p2_exactlyOneReactiveJwtDecoder...` for `local`, `aws`, `azure` profiles) using `ApplicationContextRunner` for the `aws` and `azure` variants (lightweight, no Redis needed) and `@Autowired ApplicationContext` for the `local` variant which reuses the existing `@SpringBootTest` context.

### 3. insight-service Profile Guards — G2 + G17 (Task 3)

- **`MockAiInsightService.java`** — `@Profile("!bedrock")` → `@Profile("!bedrock & !azure-ai")`; javadoc updated.
- **`MockInsightAdvisor.java`** — same profile widening + javadoc update.
- **`InfrastructureHealthLogger.java`** (insight-service only) — `@Profile("aws")` → `@Profile({"aws","azure"})`; javadoc updated. The api-gateway copy is unchanged (backlog B1).

### 4. Spring AI Azure OpenAI Dependency + YAML (Task 4)

- **`insight-service/build.gradle`** — added `spring-ai-starter-model-azure-openai` (via existing `spring-ai-bom:2.0.0-M4`) and `com.azure:azure-identity:1.13.2` (pinned explicitly).
- **`application-bedrock.yml`** — added `spring.ai.model.chat: bedrock-converse` (only permitted change per Req 13.4).
- **`application-azure-ai.yml`** (new) — `spring.ai.model.chat: azure-openai`, endpoint from `${AZURE_OPENAI_ENDPOINT}`, `api-key` commented out (Managed Identity default), `deployment-name: ${AZURE_OPENAI_DEPLOYMENT:gpt-4o-mini}`, `temperature: 0.2`. Env-var contract documented for Terraform task 11.4.

### 5. Azure OpenAI Adapters + P1 Validator (Task 5)

New production classes under `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/`:

- **`AzureOpenAiInsightService.java`** — `@Service @Profile("azure-ai")`, implements `AiInsightService`. Mirrors `BedrockAiInsightService`: same `SYSTEM_PROMPT`, `@Cacheable(SENTIMENT_CACHE)`, `AdvisorUnavailableException` handling, package-private `buildPrompt` helper.
- **`AzureOpenAiInsightAdvisor.java`** — `@Component @Profile("azure-ai")`, implements `InsightAdvisor`. Mirrors `BedrockInsightAdvisor`: `defaultSystem(SYSTEM_PROMPT)` in constructor, `@Cacheable(PORTFOLIO_ANALYSIS_CACHE)`, `.entity(AnalysisResult.class)` JSON decoding, `Math.clamp` risk score clamping.
- **`AiProviderProfileValidator.java`** — `@Configuration` (no `@Profile`, always active). `@PostConstruct validate()` throws `IllegalStateException` naming both `bedrock` and `azure-ai` when both are simultaneously active (P1 JVM-side enforcement).

New test classes under `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/`:

- **`AiProviderProfileValidatorPropertyTest.java`** (P1, optional) — jqwik `@Property` over all subsets of `{local, prod, aws, azure, bedrock, azure-ai}` using `MockEnvironment` directly (no Spring context boot). Validates the mutual-exclusion logic of `AiProviderProfileValidator.validate()` in isolation: asserts `IllegalStateException` naming both profiles when both are present, and clean completion otherwise. **Note:** Task 5.4 also prescribed asserting exactly one `AiInsightService` and one `InsightAdvisor` bean when startup succeeds — that bean-uniqueness assertion was not included here (it requires a context boot) and is instead covered by `AiAdapterUniquenessPropertyTest` (P3, task 5.5) below.
- **`AiAdapterUniquenessPropertyTest.java`** (P3, optional) — `ApplicationContextRunner` with all 6 adapter beans registered plus mock `ChatClient.Builder` / `MarketDataService`. Three `@Test` methods for `local`, `bedrock`, `azure-ai` profiles — each asserts exactly one `AiInsightService` and one `InsightAdvisor` bean of the correct type. Covers the bean-uniqueness assertions from both P1 (task 5.4) and P3 (task 5.5).
- **`ChatModelPrimarySelectionPropertyTest.java`** (P4, optional) — `ApplicationContextRunner` with both AI auto-configurations loaded. Two `@Test` methods toggle `spring.ai.model.chat` between `bedrock-converse` and `azure-openai` and assert the primary `ChatModel` bean class matches the selected provider.

Also fixed (shared path, not Azure-only):
- **`AiConfig.java`** — added `@ConditionalOnBean(ChatModel.class)` to `chatClientBuilder` (see Authorised Changes table above).
- **`MarketSummaryIntegrationTest.java`** — added `spring.ai.model.chat=none` + placeholder Azure endpoint (see Authorised Changes table above).

### 6. per-service `application-azure.yml` Overlays (Task 7)

Four new Spring profile YAML files (no `localhost` references, no CloudFront/AWS-specific config):

| File | Key settings |
|------|-------------|
| `api-gateway/src/main/resources/application-azure.yml` | `management.health.redis.enabled: false` (cold-start DNS race); `app.cors.allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:https://*.azurestaticapps.net}` |
| `portfolio-service/src/main/resources/application-azure.yml` | `fx.azure.rates-url`, `fx.azure.refresh-cron`; `spring.cache.type: simple` (ConcurrentMapCacheManager — not Caffeine) |
| `market-data-service/src/main/resources/application-azure.yml` | `market-data.refresh.enabled: true`, `market-data.hydration.enabled: true`; seeds disabled; `management.health.mongodb.enabled: true` |
| `insight-service/src/main/resources/application-azure.yml` | `management.health.redis.enabled: false` only (AI config lives in `application-azure-ai.yml`) |

Task 6 (`Extend api-gateway PreservationPropertyTest for P2`) is covered in §2 above — the P2 test methods were added to the existing `PreservationPropertyTest` class.

### 7. Azure-specific Dockerfiles (Task 9)

Four new `Dockerfile.azure` files (two-stage, `mcr.microsoft.com/openjdk/jdk:21-mariner`):

| File | Port | Excludes from settings.gradle |
|------|------|-------------------------------|
| `api-gateway/Dockerfile.azure` | 8080 | portfolio-service, market-data-service, insight-service |
| `portfolio-service/Dockerfile.azure` | 8081 | api-gateway, market-data-service, insight-service |
| `market-data-service/Dockerfile.azure` | 8082 | api-gateway, portfolio-service, insight-service |
| `insight-service/Dockerfile.azure` | 8083 | api-gateway, portfolio-service, market-data-service |

Key differences from AWS Dockerfiles: no Lambda Web Adapter, no jlink/jdeps custom JRE, no `JAVA_HOME`/`PATH` overrides, no `AWS_LWA_*` env vars. Uses `tdnf` (Mariner package manager). `ARG RUNTIME_BASE` allows overriding the runtime base image at build time.

### 8. Terraform Relocation (Task 10)

- All files under `infrastructure/terraform/` moved to `infrastructure/terraform/aws/` (pure relocation, no content changes — git records these as renames). The `infrastructure/terraform/` directory is now a parent container for `aws/` and `azure/` subdirectories.
- Added `infrastructure/terraform/aws/backend-aws.hcl` — S3 backend config template with commented-out placeholders.

### 9. Azure Terraform Root (Task 11)

New directory: `infrastructure/terraform/azure/`

**Root files:**
- `versions.tf` — `azurerm ~> 4.0`, `required_version >= 1.6.0`, backend block (real config injected via `-backend-config=backend-azure.hcl`)
- `providers.tf` — OIDC auth (`use_oidc = true`), no `client_secret`
- `variables.tf` — 23 variables including `auth_jwt_secret` validation (≥ 32 chars)
- `main.tf` — complete resource definitions (see below)
- `outputs.tf` — 6 outputs: `api_gateway_fqdn`, `acr_login_server`, `static_web_app_default_hostname`, 3 internal FQDNs
- `terraform.tfvars.example`, `backend-azure.hcl.example`
- `backend-azure.hcl` added to repo-root `.gitignore`

**`main.tf` resources (in dependency order):**
1. `azurerm_resource_group.main` — `wealth-azure-${var.environment}-rg`
2. `azurerm_container_registry.main` — `wealth${var.environment}acr` (Basic SKU, admin disabled)
3. `azurerm_log_analytics_workspace.main` — `wealth-${var.environment}-la` (PerGB2018, 30-day retention)
4. `azurerm_container_app_environment.main` — `wealth-${var.environment}-aca-env`
5. `azurerm_static_web_app.frontend` — `wealth-${var.environment}-swa` (Free tier, `"centralus"` hardcoded — SWA Free tier region constraint)
6. `azurerm_cognitive_account.openai` — `wealth-${var.environment}-aoai` (OpenAI, S0, `var.openai_location`)
7. `azurerm_cognitive_deployment.gpt4o_mini` — `gpt-4o-mini` model v2024-07-18, `var.openai_deployment_capacity` TPM
8. Four `module.*` Container App blocks (api-gateway external, others internal)
9. `azurerm_role_assignment.insight_openai` — `Cognitive Services OpenAI User` on the OpenAI account

**`modules/container-app/`:**
- `azurerm_container_app` with `SystemAssigned` identity, ACR pull via managed identity, dynamic `env`/`secret_env`/`secrets` blocks
- `azurerm_role_assignment "acr_pull"` — `AcrPull` on the ACR
- Outputs: `app_fqdn`, `identity_principal_id`

**Key wiring:**
- `SPRING_PROFILES_ACTIVE`: api-gateway/portfolio/market-data = `prod,azure`; insight-service = `prod,azure,azure-ai`
- `AZURE_OPENAI_ENDPOINT` and `AZURE_OPENAI_DEPLOYMENT` wired from Terraform resources onto insight-service
- `AZURE_OPENAI_API_KEY` intentionally absent — Managed Identity is the default auth path

### 10. Terraform Plan-Assertion Scripts (Task 12)

New mandatory scripts under `infrastructure/terraform/azure/scripts/`:

- **`assert_plan.py`** (P1 IaC-side) — validates no `azurerm_container_app` has both `bedrock` AND `azure-ai` in `SPRING_PROFILES_ACTIVE`. Navigates `change.after.template[0].container[0].env`. Gracefully skips null/secret-backed values. Exit 0 on pass, exit 1 on violation.
- **`test_acr_pull_property.py`** (P5 IaC-side) — validates every `azurerm_container_app` has `identity[0].type == "SystemAssigned"`, `registry[0].identity == "system"`, and a matching `AcrPull` `azurerm_role_assignment` in the same module prefix. Handles `principal_id = "(known after apply)"` via module-prefix matching. Exit 0 on pass, exit 1 on any failure.

Both scripts: stdlib-only (`json`, `sys`), no external dependencies.

### 11. GitHub Actions Workflows (Task 13)

**`.github/workflows/deploy-azure.yml`** (new):
- Triggers: `workflow_dispatch` + `push` to `main` (6 path filters)
- Top-level env: `AZURE_RG=wealth-azure-prod-rg`, `ACR_NAME=wealthprodacr`, `ACA_ENV=wealth-prod-aca-env`
- Matrix job over 4 services; OIDC auth via `azure/login@v2` (no `client-secret`)
- Steps: checkout → OIDC login → ACR login → `docker build -f Dockerfile.azure` → push → `az containerapp update` → polling step (sleep 30 + up to 20 × 30s polls on `provisioningState`)

**`.github/workflows/terraform-azure.yml`** (new):
- Triggers: `workflow_dispatch` (choice input `action: plan|apply`) + `pull_request` on Azure Terraform paths
- `concurrency: group: terraform-azure-${{ github.repository }}`
- `permissions: id-token: write, contents: read, pull-requests: write`
- `defaults.run.working-directory: infrastructure/terraform/azure`
- Plan path (`pull_request` or `action=plan`): `init -backend=false` → validate → plan → show JSON → `assert_plan.py` (P1, MANDATORY) → `test_acr_pull_property.py` (P5, MANDATORY)
- Apply path (`action=apply` only): provision `backend-azure.hcl` from `AZURE_BACKEND_HCL` secret → `init -backend-config` → validate → `apply -auto-approve`

---

## Test Results (Final Checkpoint)

| Service | Tests | Failures |
|---------|-------|----------|
| api-gateway | 143 | 0 |
| portfolio-service | 105 | 0 |
| market-data-service | 23 | 0 |
| insight-service | 1865 | 0 |
| common-dto | 3 | 0 |

Property tests passing: P1 (profile mutual exclusion), P2 (JwtDecoder presence), P3 (AI adapter uniqueness), P4 (ChatModel primary selection).

**Azure Terraform root:** `terraform init -backend=false && terraform validate` exits 0.

**AWS Terraform root:** Directory structure intact after relocation (all files correctly renamed to `infrastructure/terraform/aws/`). `terraform init -backend=false` fails with a pre-existing error in `modules/compute/main.tf` (import blocks that predate this spec — confirmed via git history). This is not a regression from Task 10, which moved files without modifying content per Req 13.7.

**Python assertion scripts:** Both `assert_plan.py` and `test_acr_pull_property.py` exit 0 against a synthetic fixture representing a valid plan with 4 Container Apps. A real `terraform plan` JSON export requires Azure credentials and was not run locally; the scripts are invoked against a real plan by `terraform-azure.yml` in CI on every PR.

---

## Files Changed Summary

| Category | New Files | Modified Files |
|----------|-----------|----------------|
| Spring Boot (main) | 3 new adapters + 1 validator + 5 YAML overlays | 7 modified (profiles, deps, AiConfig, InfrastructureHealthLogger, MockAiInsightService, MockInsightAdvisor, JwtDecoderConfig) |
| Spring Boot (test) | 3 new property test classes | 3 modified (PreservationPropertyTest, MarketSummaryIntegrationTest, BedrockPropertyTest) |
| Dockerfiles | 4 `Dockerfile.azure` | 0 (AWS Dockerfiles untouched) |
| Terraform | Full `azure/` root + module (15+ files) | 0 (AWS modules untouched, relocated only) |
| GitHub Actions | 2 new workflows | 0 (existing workflows untouched) |
| Build | 0 | 1 (`build.gradle` toolchain 25→21) |

---

## Known Deviations from Spec

### Task 5.4 — P1 property test implementation

Task 5.4 prescribed a jqwik `@Property` test that boots a `SpringApplicationContext` via `SpringApplicationBuilder` and asserts both (a) startup fails with `IllegalStateException` when both `bedrock` and `azure-ai` are active, and (b) the context exposes exactly one `AiInsightService` and one `InsightAdvisor` bean when startup succeeds.

The implemented `AiProviderProfileValidatorPropertyTest` covers assertion (a) using `MockEnvironment` directly (no Spring context boot), which is faster and sufficient to validate the validator's logic in isolation. Assertion (b) — the bean-uniqueness check — was not included here because it requires a context boot; it is instead fully covered by `AiAdapterUniquenessPropertyTest` (P3, task 5.5), which uses `ApplicationContextRunner` to assert exactly one bean of each type per profile. The end-to-end coverage is equivalent; the split across two test classes is a deliberate departure from the literal task wording.

---

## Backlog (Out of Scope for This Phase)

Per `design.md` §6 and `TODO.md` §3, the following items are explicitly deferred. Note: `requirements.md` uses different B-numbers for a partially overlapping backlog — the labels below are from `design.md`.

- **B1** — Widen api-gateway `InfrastructureHealthLogger` `@Profile` to include `azure` (matches `requirements.md` B1)
- **B2** — Frontend Azure Static Web App deployment step in `deploy-azure.yml`
- **B3** — Synthetic monitoring workflow for Azure
- **B4** — Azure-specific E2E test profile
- **B5** — Upstash Redis replacement with Azure Cache for Redis (if ElastiCache Free Tier expires)

`requirements.md` additionally lists: align AWS Dockerfiles from `amazoncorretto:25` → `21` (B2), migration to managed `java21` Lambda runtime (B3), per-cloud Kafka consumer group IDs (B4). These are separate from the `design.md` backlog items above.
