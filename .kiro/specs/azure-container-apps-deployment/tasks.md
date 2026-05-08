# Implementation Plan: Azure Container Apps Deployment

## Overview

Convert the feature design into a series of prompts for a code-generation LLM that will implement each step with incremental progress. Make sure that each prompt builds on the previous prompts, and ends with wiring things together. There should be no hanging or orphaned code that isn't integrated into a previous step. Focus ONLY on tasks that involve writing, modifying, or testing code.

This plan implements `design.md` end-to-end: the JDK 21 toolchain alignment, the four `@Profile` widenings (G1, G2, G17), the two new Azure OpenAI adapters plus the `AiProviderProfileValidator` (P1 enforcement), five new Spring YAML overlays, four new `Dockerfile.azure` files, the Terraform relocation + new Azure root with a reusable `container-app` module, two plan-assertion Python scripts, and two new GitHub Actions workflows — all additive, with zero modifications to existing AWS Dockerfiles, CDK stacks, AWS Terraform module contents, existing workflows, or `application.yml` / `application-prod.yml` / `application-local.yml` / `application-aws.yml`.

Property-based tests (P1 JVM-side, P2, P3, P4 via jqwik / Spring `@SpringBootTest`; P5 + P1 IaC-side via Python plan-assertion scripts) are included close to the implementation they validate. The IaC-side P1 + P5 scripts (tasks 12.1, 12.2) are MANDATORY because the Terraform workflow in task 13.2 invokes them unconditionally; the JVM-side property tests (5.4, 5.5, 5.6, 6.1) remain optional for MVP per the Notes section.

## Tasks

- [x] 1. Align Gradle toolchain to JDK 21 and sweep cosmetic item
  - [ ] 1.1 Downgrade root `build.gradle` Java toolchain from 25 to 21
    - Edit the single `languageVersion = JavaLanguageVersion.of(25)` line in the root `build.gradle` to `JavaLanguageVersion.of(21)`
    - Leave every other file in the source tree unchanged (this is the only root-build.gradle modification permitted by Req 13.2)
    - Verify `./gradlew build` succeeds locally against the JDK 21 toolchain for all four services and that the produced fat JARs contain Java 21 bytecode (class-file major version 65)
    - _Requirements: 0.1, 0.2, 0.3, 13.2_

  - [ ] 1.2 Clean up stale Ollama javadoc in Bedrock property test
    - Remove the `"Migrated from the removed OllamaAiInsightServicePropertyTest"` reference from the class-level javadoc of `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/BedrockAiInsightServicePropertyTest.java`
    - _Requirements: 16.1_

- [x] 2. Widen api-gateway profile wiring for Azure (G1)
  - [ ] 2.1 Widen `JwtDecoderConfig.hmacJwtDecoder` and update its preservation test
    - In `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java`: change `@Profile({"local","aws"})` → `@Profile({"local","aws","azure"})` on `hmacJwtDecoder`, and update the `IllegalStateException` message to reference `local/aws/azure`
    - In `api-gateway/src/test/java/com/wealth/gateway/PreservationPropertyTest.java`: update `jwtDecoderConfigHasCorrectProfileAnnotations` to use `containsExactlyInAnyOrder("local","aws","azure")` with an updated `@as(...)` message
    - These two edits must land together — per design §3.1 the preservation test cannot remain green after the config widens without the assertion update
    - _Requirements: 2.1, 2.3, 2.5, 13.10_

- [x] 3. Widen insight-service profile guards (G2 + G17)
  - [ ] 3.1 Widen mock AI bean guards to exclude `azure-ai`
    - `MockAiInsightService` and `MockInsightAdvisor` (both under `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/`): change `@Profile("!bedrock")` → `@Profile("!bedrock & !azure-ai")` on each
    - Update the class-level javadoc on both classes to describe activation as "whenever neither bedrock nor azure-ai profile is enabled"
    - _Requirements: 3.1, 3.2, 3.6_

  - [ ] 3.2 Widen insight-service `InfrastructureHealthLogger` `@Profile`
    - Edit `insight-service/src/main/java/com/wealth/insight/InfrastructureHealthLogger.java`: change `@Profile("aws")` → `@Profile({"aws","azure"})`
    - Do NOT modify the api-gateway copy at `api-gateway/src/main/java/com/wealth/gateway/InfrastructureHealthLogger.java` — that widening is backlog B1, out of scope
    - _Requirements: 14.1, 14.2, 14.3_

- [x] 4. Add Spring AI Azure OpenAI dependency and profile YAML
  - [ ] 4.1 Add Spring AI Azure OpenAI + azure-identity to `insight-service/build.gradle`
    - Add `implementation 'org.springframework.ai:spring-ai-starter-model-azure-openai'` (resolved via the existing `spring-ai-bom:2.0.0-M4`)
    - Add `implementation 'com.azure:azure-identity:1.13.2'` (pinned explicitly; defer to a managing BOM if the workspace already provides one)
    - Keep the existing `spring-ai-starter-model-bedrock-converse` dependency unchanged
    - _Requirements: 4.1_

  - [ ] 4.2 Add `spring.ai.model.chat: bedrock-converse` to `application-bedrock.yml`
    - The ONLY modification permitted to this file per Req 13.4: add `spring.ai.model.chat: bedrock-converse` under `spring.ai`
    - Leave every other key (Bedrock region, credentials, Converse chat options) untouched
    - _Requirements: 4.2, 13.4_

  - [ ] 4.3 Create `insight-service/src/main/resources/application-azure-ai.yml`
    - Include `spring.ai.model.chat: azure-openai`
    - Include `spring.ai.azure.openai.endpoint: ${AZURE_OPENAI_ENDPOINT}`
    - Leave `api-key` commented out so `DefaultAzureCredential` resolves via Managed Identity by default; include the documented switch for API-key auth
    - Include `spring.ai.azure.openai.chat.options.deployment-name: ${AZURE_OPENAI_DEPLOYMENT:gpt-4o-mini}` and `temperature: 0.2`
    - No `localhost` references
    - The same env var names (`AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_DEPLOYMENT`) must be wired onto the insight-service Container App in task 11.4 — they are the contract between this overlay and Terraform
    - _Requirements: 4.3, 5.1, 5.4, 8.5, 8.6_

- [x] 5. Implement Azure OpenAI adapters and P1 validator
  - [ ] 5.1 Create `AzureOpenAiInsightService`
    - Path: `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/AzureOpenAiInsightService.java`
    - `@Service`, `@Profile("azure-ai")`, implements `com.wealth.insight.AiInsightService`
    - Inject `ChatClient.Builder` and `MarketDataService`; mirror `BedrockAiInsightService`'s SYSTEM_PROMPT + `@Cacheable(SENTIMENT_CACHE, key="#ticker")` + `AdvisorUnavailableException` handling
    - Package-private `buildPrompt(String ticker, TickerSummary summary)` helper for prompt assembly
    - _Requirements: 3.3, 4.5_

  - [ ] 5.2 Create `AzureOpenAiInsightAdvisor`
    - Path: `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/AzureOpenAiInsightAdvisor.java`
    - `@Component`, `@Profile("azure-ai")`, implements `com.wealth.insight.advisor.InsightAdvisor`
    - Inject `ChatClient.Builder`, set the safety-guardrail SYSTEM_PROMPT via `defaultSystem(...)`
    - `@Cacheable(PORTFOLIO_ANALYSIS_CACHE, key="#portfolio.id()")`; entity-response JSON decoding into `AnalysisResult`; private `clampRiskScore` using `Math.clamp(..., 1, 100)`
    - Mirror `BedrockInsightAdvisor`'s exception handling (rethrow `AdvisorUnavailableException`, wrap everything else)
    - _Requirements: 3.4, 4.5_

  - [ ] 5.3 Create `AiProviderProfileValidator` — P1 JVM-side enforcement
    - Path: `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/AiProviderProfileValidator.java`
    - `@Configuration` (no `@Profile` — always active) with a `@PostConstruct validate()` method that reads `environment.getActiveProfiles()` and throws `IllegalStateException` when the active set contains both `bedrock` AND `azure-ai`; the message must name both profiles explicitly
    - _Requirements: 1.1, 1.2_

  - [ ]* 5.4 Write property test for P1 — profile mutual exclusion
    - **Property P1: Profile Mutual Exclusion**
    - **Validates: Requirements 1.1, 1.2, 15.1**
    - jqwik (`@Property`) test under `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/` that enumerates profile combinations from `{local, prod, aws, azure, bedrock, azure-ai}` via `@ForAll` + `@Size` over a `Set<String>`
    - For each combination: boot a `SpringApplicationContext` via `new SpringApplicationBuilder(...)` + `ActiveProfiles` equivalent; assert startup fails with `IllegalStateException` naming both `bedrock` and `azure-ai` when both are present; assert clean startup otherwise
    - Assert the context exposes exactly one `AiInsightService` bean and exactly one `InsightAdvisor` bean when startup succeeds
    - _Requirements: 1.1, 1.2, 15.1, 15.6_

  - [ ]* 5.5 Write property test for P3 — AI adapter uniqueness
    - **Property P3: AI Adapter Uniqueness**
    - **Validates: Requirements 3.6, 3.7, 3.8, 15.3**
    - `@SpringBootTest` parameterised over `@ActiveProfiles` ∈ {`local`, `bedrock`, `azure-ai`}: assert `ctx.getBeansOfType(AiInsightService.class).size() == 1` and `ctx.getBeansOfType(InsightAdvisor.class).size() == 1` in each case, and that the resolved bean class matches the expected Mock / Bedrock / Azure variant
    - _Requirements: 3.6, 3.7, 3.8, 15.3, 15.6_

  - [ ]* 5.6 Write property test for P4 — ChatModel primary selection
    - **Property P4: Chat Model Primary Selection**
    - **Validates: Requirements 4.4, 4.5, 15.4**
    - Spring context test with BOTH Spring AI starters on the classpath; use `@DynamicPropertySource` to toggle `spring.ai.model.chat` between `bedrock-converse` and `azure-openai`
    - Assert the primary `ChatModel` bean name (via `BeanFactory.getBeanNamesForType(ChatModel.class)` + primary qualifier inspection, or the class of `ChatClient.Builder`'s underlying model) matches the selected provider
    - _Requirements: 4.4, 4.5, 15.4, 15.6_

- [x] 6. Extend api-gateway `PreservationPropertyTest` for P2
  - [ ]* 6.1 Add P2 property coverage by extending the existing `PreservationPropertyTest` class
    - **Property P2: JwtDecoder Presence**
    - **Validates: Requirements 2.2, 2.4, 15.2**
    - Extend the existing `api-gateway/src/test/java/com/wealth/gateway/PreservationPropertyTest.java` with additional parameterised test methods (do NOT create a new test class — design §4 P2 prescribes extending this class so the P2 assertions colocate with the widened-annotation assertion added in task 2.1)
    - For each active profile in `{local, aws, azure}` boot a `@SpringBootTest` (or use `ApplicationContextRunner` with `withPropertyValues("spring.profiles.active=...")` for a lighter fixture) and assert `ctx.getBeansOfType(ReactiveJwtDecoder.class).size() == 1`; assert `oauth2ResourceServer().jwt()` wiring resolves the bean without a `NoSuchBeanDefinitionException` during context refresh
    - _Requirements: 2.2, 2.4, 15.2, 15.6_

- [x] 7. Create per-service `application-azure.yml` overlays
  - [ ] 7.1 Create `api-gateway/src/main/resources/application-azure.yml`
    - Include `management.health.redis.enabled: false` (cold-start DNS race rationale)
    - Include `app.cors.allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:https://*.azurestaticapps.net}` (env-var-driven; widen/narrow per environment)
    - No `localhost` references; no CloudFront/origin-verify config (AWS-specific, filter short-circuits when env var absent)
    - _Requirements: 8.1, 8.5_

  - [ ] 7.2 Create `portfolio-service/src/main/resources/application-azure.yml`
    - Include `fx.azure.rates-url: https://open.er-api.com/v6/latest/USD` and `fx.azure.refresh-cron: "0 0 6 * * *"`
    - Include `spring.cache.type: simple` — value is `simple`, which in Spring Boot resolves to the built-in `ConcurrentMapCacheManager` (plain in-memory `ConcurrentHashMap`-backed cache). Describe it in the YAML comment as "Spring simple `ConcurrentMapCacheManager` in-memory" — do NOT describe it as Caffeine, and do NOT change the value from `simple`. Upstash Redis stays reserved for rate limiting / sentiment caching.
    - No `localhost` references
    - _Requirements: 8.2, 8.5_

  - [ ] 7.3 Create `market-data-service/src/main/resources/application-azure.yml`
    - Include `market-data.refresh.enabled: true` and `market-data.hydration.enabled: true` (ACA long-lived containers make scheduled refresh reliable, unlike Lambda)
    - Include `market.seed.enabled: false` and `market-data.baseline-seed.enabled: false`
    - Include `management.health.mongodb.enabled: true` (custom health indicator runs `{ ping: 1 }` against the scoped DB)
    - No `localhost` references
    - _Requirements: 8.3, 8.5_

  - [ ] 7.4 Create `insight-service/src/main/resources/application-azure.yml`
    - Include `management.health.redis.enabled: false` only; AI config lives in `application-azure-ai.yml`
    - No `localhost` references
    - _Requirements: 8.4, 8.5_

- [x] 8. Checkpoint — AWS path preservation
  - Run `./gradlew check` across all four services and confirm every existing test still passes (including `PreservationPropertyTest` under `@ActiveProfiles("local")`)
  - Verify none of `application.yml`, `application-prod.yml`, `application-local.yml`, `application-aws.yml`, `<service>/Dockerfile`, `infrastructure/lib/*.ts`, `.github/workflows/deploy.yml`, or `.github/workflows/terraform.yml` were modified beyond the minimal diffs authorised by Req 13.4 (bedrock YAML single-key add) and Req 13.2 (root `build.gradle` one-line toolchain)
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 13.1, 13.3, 13.4, 13.5, 13.6, 13.8, 13.9, 13.10, 13.11_

- [x] 9. Create Azure-specific Dockerfiles (native optimization, two-stage)
  - [ ] 9.1 Create `api-gateway/Dockerfile.azure`
    - Two stages only: `FROM mcr.microsoft.com/openjdk/jdk:21-mariner AS builder` and `ARG RUNTIME_BASE=mcr.microsoft.com/openjdk/jdk:21-mariner` + `FROM ${RUNTIME_BASE} AS runtime`
    - Builder: copy `gradlew`, `gradle/`, `build.gradle`, `settings.gradle`, `common-dto/`, `api-gateway/`; trim `settings.gradle` to exclude the three non-target services; install `findutils` via `tdnf`; run `./gradlew :api-gateway:bootJar --no-daemon`
    - Runtime: `COPY --from=builder /workspace/api-gateway/build/libs/*.jar app.jar`; `EXPOSE 8080`; `ENTRYPOINT ["java","-jar","/app/app.jar"]`
    - MUST NOT contain: `COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter`, `COPY --from=jre-builder`, `JAVA_HOME` / `PATH` overrides, `AWS_LWA_PORT`, `AWS_LWA_READINESS_CHECK_PATH`
    - Leave `api-gateway/Dockerfile` (AWS variant) untouched
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10_

  - [ ] 9.2 Create `portfolio-service/Dockerfile.azure`
    - Same structure as 9.1 with `api-gateway` → `portfolio-service` substitutions in COPY paths and the Gradle task; `sed` excludes `api-gateway`, `market-data-service`, `insight-service`
    - `EXPOSE 8081`; Spring Boot PID-1 entrypoint
    - Leave `portfolio-service/Dockerfile` (AWS variant) untouched
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.7, 9.8, 9.9, 9.10_

  - [ ] 9.3 Create `market-data-service/Dockerfile.azure`
    - Same structure; `sed` excludes `api-gateway`, `portfolio-service`, `insight-service`
    - `EXPOSE 8082`
    - Leave `market-data-service/Dockerfile` (AWS variant) untouched
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.7, 9.8, 9.9, 9.10_

  - [ ] 9.4 Create `insight-service/Dockerfile.azure`
    - Same structure; `sed` excludes `api-gateway`, `portfolio-service`, `market-data-service`
    - `EXPOSE 8083`
    - Leave `insight-service/Dockerfile` (AWS variant) untouched
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.7, 9.8, 9.9, 9.10_

- [x] 10. Relocate existing Terraform root into per-cloud layout
  - [ ] 10.1 Relocate `infrastructure/terraform/` → `infrastructure/terraform/aws/`
    - Move `main.tf`, `ecr.tf`, `locals.tf`, `outputs.tf`, `providers.tf`, `variables.tf`, `versions.tf`, `backend-localstack.hcl`, `terraform.tfvars.example`, and the entire `bootstrap/`, `modules/`, `scripts/` subtrees to `infrastructure/terraform/aws/<same>`
    - Additionally create `infrastructure/terraform/aws/backend-aws.hcl` for symmetry with the Azure root (optional per design §3.8 but recommended); do NOT change any file contents
    - Verify `cd infrastructure/terraform/aws && terraform init` still succeeds
    - _Requirements: 10.1, 10.3, 10.5, 13.7_

- [x] 11. Build the Azure Terraform root
  - [ ] 11.1 Scaffold `infrastructure/terraform/azure/` baseline
    - Create `versions.tf` pinning `azurerm`
    - Create `providers.tf` with `provider "azurerm" { features {} use_oidc = true client_id = var.azure_client_id tenant_id = var.azure_tenant_id subscription_id = var.azure_subscription_id }` and NO `client_secret` reference
    - Create `variables.tf` declaring `azure_client_id`, `azure_tenant_id`, `azure_subscription_id`, `environment` (default `prod`), `location` (default `centralindia`), `openai_location` (default `eastus` or similar availability-checked region), `openai_deployment_capacity` (default `10`), `image_tag`, `api_gateway_min_replicas` (default 0), `auth_jwt_secret` (sensitive), service env-var maps
    - Create empty skeleton `main.tf`, `outputs.tf` (populated in 11.6), and `terraform.tfvars.example`
    - **Backend file handling (matches design §3.8 + safety posture):** commit a template file `backend-azure.hcl.example` with placeholder values for `resource_group_name`, `storage_account_name`, `container_name`, and `key`. Gitignore the real `backend-azure.hcl` by adding `infrastructure/terraform/azure/backend-azure.hcl` to the repo-root `.gitignore`. The PR-validation path in task 13.2 will use `terraform init -backend=false`; the apply path provisions the real `backend-azure.hcl` from a GitHub Actions secret before invoking `terraform init -backend-config=backend-azure.hcl`.
    - _Requirements: 10.2, 10.4, 10.5, 10.6_

  - [ ] 11.2 Create `infrastructure/terraform/azure/modules/container-app/`
    - `main.tf`: `azurerm_container_app` with `identity { type = "SystemAssigned" }`, `registry { server = var.acr_login_server, identity = "system" }`, `ingress { external_enabled target_port transport traffic_weight }`, `template` with `min_replicas`/`max_replicas` + `container { cpu memory image }`, dynamic `env`/`secret_env`/`secrets` blocks
    - In the same module: `azurerm_role_assignment "acr_pull"` with `scope = var.acr_id`, `role_definition_name = "AcrPull"`, `principal_id = azurerm_container_app.this.identity[0].principal_id`
    - `variables.tf`: `name`, `environment_id`, `resource_group_name`, `acr_id`, `acr_login_server`, `image_repository`, `image_tag`, `target_port`, `external_ingress`, `min_replicas`, `max_replicas`, `cpu`, `memory`, `env_vars` (map), `secret_env_vars` (map), `secrets` (sensitive map)
    - `outputs.tf`: `app_fqdn`, `identity_principal_id`
    - `versions.tf`: same `azurerm` pin as the root
    - _Requirements: 6.1, 6.2, 6.3, 7.2, 7.3, 7.4, 11.5, 11.9_

  - [ ] 11.3 Add core resources to `infrastructure/terraform/azure/main.tf`
    - All resource names MUST follow the patterns below (aligned with the `AZURE_RG=wealth-azure-prod-rg`, `ACR_NAME=wealthprodacr`, `ACA_ENV=wealth-prod-aca-env` env vars consumed by `deploy-azure.yml` in task 13.1):
      - Resource group: `name = "wealth-azure-${var.environment}-rg"`, `location = var.location` (do NOT hard-code `"centralindia"`; the default lives on `var.location` per task 11.1)
      - ACR: `name = "wealth${var.environment}acr"` (no separator — ACR names must be alphanumeric, 5–50 chars, globally unique), `sku = "Basic"`, `admin_enabled = false`
      - Log Analytics: `name = "wealth-${var.environment}-la"`, sku `PerGB2018`, retention 30 days
      - ACA environment: `name = "wealth-${var.environment}-aca-env"`, linked to the Log Analytics workspace above
      - Static Web App: `name = "wealth-${var.environment}-swa"`, `location = "centralus"` (SWA Free tier region availability constraint — do NOT use `var.location`), `sku_tier = "Free"`, `sku_size = "Free"`
    - _Requirements: 6.4, 10.2, 11.1, 11.2, 11.3, 11.8_

  - [ ] 11.4 Wire four Container App modules into `main.tf`
    - `module "api_gateway"` — external ingress, `target_port = 8080`, `min_replicas = var.api_gateway_min_replicas` (default 0), `max_replicas = 3`, `cpu = 0.5`, `memory = "1Gi"`
    - `module "portfolio_service"` — internal ingress, `target_port = 8081`
    - `module "market_data_service"` — internal ingress, `target_port = 8082`
    - `module "insight_service"` — internal ingress, `target_port = 8083`
    - `SPRING_PROFILES_ACTIVE` for each app MUST contain at most one of `{bedrock, azure-ai}` (insight-service uses `prod,azure,azure-ai`; the rest use `prod,azure`)
    - api-gateway env must set `PORTFOLIO_SERVICE_URL=http://portfolio-service`, `MARKET_DATA_URL=http://market-data-service`, `INSIGHT_URL=http://insight-service` (no port segments)
    - **Wire Azure OpenAI env vars onto the insight-service module (must align 1:1 with the env-var names read by `application-azure-ai.yml` in task 4.3):**
      - `AZURE_OPENAI_ENDPOINT = azurerm_cognitive_account.openai.endpoint`
      - `AZURE_OPENAI_DEPLOYMENT = azurerm_cognitive_deployment.gpt4o_mini.name` (or the literal `"gpt-4o-mini"` — either is acceptable; the deployment-name output is preferred because it stays correct if the deployment is ever renamed)
      - `AZURE_OPENAI_API_KEY` MUST NOT be declared as a secret or env var on the insight-service Container App. The Managed Identity path is the default; the API-key path is documented in design §3.4 but is out of scope unless explicitly opted in.
    - _Requirements: 1.3, 6.5, 7.1, 7.2, 7.3, 7.4, 11.4, 11.5, 11.6, 11.9, 5.1, 5.4, 5.5_

  - [ ] 11.5 Provision Azure OpenAI and grant insight-service access
    - `azurerm_cognitive_account "openai"` — kind `OpenAI`, `sku_name = "S0"`, `location = var.openai_location`, `name = "wealth-${var.environment}-aoai"`
    - `azurerm_cognitive_deployment "gpt4o_mini"` — `name = "gpt-4o-mini"`, `model { format = "OpenAI", name = "gpt-4o-mini", version = "2024-07-18" }`, `sku { name = "Standard", capacity = 10 }`. The `capacity = 10` value matches design §3.8; an acceptable variant is `sku { name = "Standard", capacity = var.openai_deployment_capacity }` with `var.openai_deployment_capacity` defaulting to `10` (declared in task 11.1) so operators can dial throughput up/down without editing `main.tf`.
    - `azurerm_role_assignment "insight_openai"` — `role_definition_name = "Cognitive Services OpenAI User"`, `scope = azurerm_cognitive_account.openai.id`, `principal_id = module.insight_service.identity_principal_id`
    - _Requirements: 5.2, 5.3, 5.5, 11.7_

  - [ ] 11.6 Populate Azure Terraform root outputs
    - Create `infrastructure/terraform/azure/outputs.tf` (initially scaffolded empty in task 11.1) with the following outputs wired from the resources/modules created in tasks 11.3–11.5:
      - `api_gateway_fqdn` — value = `module.api_gateway.app_fqdn`; description "Public ingress FQDN of the api-gateway Container App (used as the API origin by the Static Web App)."
      - `acr_login_server` — value = `azurerm_container_registry.main.login_server`; description "ACR login server (consumed by `deploy-azure.yml` when pushing service images)."
      - `static_web_app_default_hostname` — value = `azurerm_static_web_app.frontend.default_host_name`; description "Default `*.azurestaticapps.net` hostname for the frontend SWA."
      - Optionally, for diagnostics: `portfolio_service_internal_fqdn`, `market_data_service_internal_fqdn`, `insight_service_internal_fqdn` — each sourced from the corresponding module's `app_fqdn` output; mark description as "Internal FQDN (useful for in-environment debugging; external callers MUST route through `api_gateway_fqdn`)."
    - This task depends on outputs produced by 11.4 (the four `module.*.app_fqdn` values) and 11.5 (the cognitive-deployment resource id is not exported here, so nothing additional needed from 11.5 — but the resource must exist before `terraform plan` succeeds)
    - _Requirements: 10.5, 10.6, 11.1_

- [x] 12. Write Terraform plan-assertion scripts (P1 + P5 IaC-side) — MANDATORY
  - [ ] 12.1 Write `infrastructure/terraform/azure/scripts/assert_plan.py` — P1 IaC-side
    - **Property P1 (IaC side): Profile Mutual Exclusion in Terraform plan**
    - **Validates: Requirements 1.3, 1.4, 15.1**
    - Parse `tfplan.json` from argv[1]; for every `azurerm_container_app` planned resource, read the `template.container.env[name=SPRING_PROFILES_ACTIVE].value`; split on comma and assert the result set contains at most one of `{bedrock, azure-ai}`; exit non-zero with a clear message otherwise
    - This task is MANDATORY (not marked with `*`) because `.github/workflows/terraform-azure.yml` in task 13.2 invokes it unconditionally on every PR and every `action=plan` dispatch — the workflow will fail if the script is missing
    - _Requirements: 1.3, 1.4, 15.1, 15.7_

  - [ ] 12.2 Write `infrastructure/terraform/azure/scripts/test_acr_pull_property.py` — P5 IaC-side
    - **Property P5: ACR Pull Authorization**
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.5, 15.5**
    - Parse `tfplan.json`; assert every `azurerm_container_app` resource has `identity[0].type == "SystemAssigned"` AND `registry[0].identity == "system"`
    - For each such Container App, assert a matching `azurerm_role_assignment` exists in the plan with `role_definition_name == "AcrPull"`, `principal_id` referencing the same Container App's system-assigned identity, and `scope` referencing the ACR resource id
    - Exit non-zero with a per-failure explanation otherwise
    - This task is MANDATORY (not marked with `*`) because `.github/workflows/terraform-azure.yml` in task 13.2 invokes it unconditionally on every PR and every `action=plan` dispatch — the workflow will fail if the script is missing
    - _Requirements: 6.1, 6.2, 6.3, 6.5, 15.5, 15.7_

- [x] 13. Create GitHub Actions workflows for Azure
  - [ ] 13.1 Create `.github/workflows/deploy-azure.yml`
    - **Triggers:**
      - `on.workflow_dispatch:` (manual run)
      - `on.push:` with `branches: [main]` and `paths:` filter — match design §3.9 exactly: `api-gateway/**`, `portfolio-service/**`, `market-data-service/**`, `insight-service/**`, `common-dto/**`, `.github/workflows/deploy-azure.yml`
    - **Top-level `env` block** (values must align with the Terraform resource names from task 11.3):
      - `AZURE_RG: wealth-azure-prod-rg`
      - `ACR_NAME: wealthprodacr`
      - `ACA_ENV: wealth-prod-aca-env`
    - `permissions: { id-token: write, contents: read }`
    - Matrix job over `[api-gateway, portfolio-service, market-data-service, insight-service]`
    - Use `azure/login@v2` with `client-id`, `tenant-id`, `subscription-id` inputs only; NO `client-secret`
    - Build each service with `docker build -f ${{ matrix.service }}/Dockerfile.azure -t $ACR_NAME.azurecr.io/${{ matrix.service }}:${{ github.sha }} .`, then push
    - `az containerapp update --image $ACR_NAME.azurecr.io/${{ matrix.service }}:${{ github.sha }}` — same tag as the pushed image
    - Follow with a polling step that calls `az containerapp show ... --query properties.provisioningState` and waits up to ~10 minutes for `Succeeded`, failing fast on `Failed`/`Canceled`
    - The polling step SHOULD also include an initial `sleep 30` on first-time environment creation (design §5 / G18 — belt-and-braces for ACA internal DNS NXDOMAIN propagation before the first revision is visible)
    - Leave existing `.github/workflows/deploy.yml` untouched
    - _Requirements: 7.5, 12.1, 12.3, 12.5, 12.6, 12.7, 12.8_

  - [ ] 13.2 Create `.github/workflows/terraform-azure.yml`
    - **Triggers:**
      - `on.workflow_dispatch:` with one input `action:` of type `choice`, options `[plan, apply]`, default `plan`
      - `on.pull_request:` with `paths:` filter matching design §3.9: `infrastructure/terraform/azure/**` and `.github/workflows/terraform-azure.yml`
    - **`permissions` block (re-declared here — must appear in the workflow file itself, not inherited):** `id-token: write`, `contents: read`, `pull-requests: write`
    - `azure/login@v2` via OIDC (no `client-secret`); `hashicorp/setup-terraform@v3`
    - `defaults.run.working-directory: infrastructure/terraform/azure`
    - **Step ordering (must match design §3.9 semantics — plan-path and apply-path are separate conditional branches, not a sequential pipeline):**
      - On `pull_request` or `workflow_dispatch && inputs.action == 'plan'`: run `terraform init -backend=false` (the real `backend-azure.hcl` is gitignored per task 11.1), `terraform validate`, `terraform plan -out=tfplan`, `terraform show -json tfplan > tfplan.json`, `python3 scripts/assert_plan.py tfplan.json`, `python3 scripts/test_acr_pull_property.py tfplan.json`
      - On `workflow_dispatch && inputs.action == 'apply'` (and ONLY this branch): provision the real `backend-azure.hcl` from a GitHub Actions secret (e.g., `echo "$AZURE_BACKEND_HCL" > backend-azure.hcl`), then run `terraform init -backend-config=backend-azure.hcl`, `terraform validate`, `terraform apply -auto-approve`
    - The two branches are selected by `if:` conditions on each step; `terraform apply` MUST NOT run after the assertion scripts in the same job — it runs only when the dispatch input is `apply`, so the ordering is unambiguous and an `apply` run does not need to re-run the plan-assertion scripts (design §3.9 intent)
    - Leave existing `.github/workflows/terraform.yml` untouched
    - _Requirements: 10.2, 12.2, 12.4, 12.5, 12.8, 15.7_

- [x] 14. Final checkpoint — Ensure all tests pass
  - Run `./gradlew check` for all four services and confirm every unit test, integration test, and (when enabled) property test P1/P2/P3/P4 passes
  - Run `terraform init -backend=false && terraform validate` in both `infrastructure/terraform/aws/` and `infrastructure/terraform/azure/` and confirm both exit zero
  - Verify `assert_plan.py` and `test_acr_pull_property.py` exit zero when executed against a locally-generated `terraform plan -out=tfplan` JSON export from the Azure root
  - Verify the AWS Dockerfiles, CDK sources, existing `deploy.yml`/`terraform.yml`, and AWS YAML overlays were not touched anywhere across the diff
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 10.5, 10.6, 13.1, 13.3, 13.5, 13.6, 13.7, 13.8, 15.6, 15.7_

## Notes

- **Optional-sub-task policy (two tiers):** Property-test sub-tasks are split by layer:
  - **IaC-side P1 + P5** (tasks 12.1 and 12.2) are MANDATORY — they are invoked unconditionally by `.github/workflows/terraform-azure.yml` (task 13.2) on every PR and every `action=plan` dispatch, so the workflow cannot pass without them.
  - **JVM-side P1/P2/P3/P4** (tasks 5.4, 5.5, 5.6, and 6.1) remain optional (`*`) and MAY be skipped for a faster MVP. They MUST be implemented and passing before declaring the spec production-ready (Req 15.6).
- Every task references the granular sub-requirements it fulfils for traceability. Checkpoints (tasks 8 and 14) consolidate the Req 13 preservation contract and the Req 15 correctness-properties contract.
- The design used concrete Java/Spring Boot code and Terraform HCL (no pseudocode), so no programming-language selection step is required.
- Property tests are placed close to the implementation they validate: P1/P3/P4 under insight-service next to the Azure AI adapters, P2 co-located inside the existing `PreservationPropertyTest` class under api-gateway (per design §4 P2), P1 (IaC) + P5 under `infrastructure/terraform/azure/scripts/`.
- The only cross-cloud source change is the single-line Java toolchain downgrade in the root `build.gradle` (Req 0); all other edits are additive and the existing AWS Dockerfiles, CDK stacks, AWS Terraform module contents, and existing workflows remain untouched (Req 13).
- Backlog items B1–B5 captured in design §6 and `TODO.md` §3 are explicitly out of scope for this plan — do not interleave them.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "3.1", "3.2", "4.1", "4.2", "4.3", "7.1", "7.2", "7.3", "7.4", "9.1", "9.2", "9.3", "9.4", "10.1", "11.1", "12.1", "12.2"] },
    { "id": 1, "tasks": ["5.1", "5.2", "5.3", "6.1", "11.2", "11.3", "13.1", "13.2"] },
    { "id": 2, "tasks": ["5.4", "5.5", "5.6", "11.4"] },
    { "id": 3, "tasks": ["11.5"] },
    { "id": 4, "tasks": ["11.6"] }
  ]
}
```
