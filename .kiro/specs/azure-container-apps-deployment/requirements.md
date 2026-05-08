# Azure Container Apps Deployment — Requirements

**Spec:** `azure-container-apps-deployment`
**Workflow:** Design-First — requirements derived from `design.md`
**Source analysis:** `docs/analysis/azure-container-migration-analysis.md` (validated, endorsed)
**Constraint:** Additive. Zero AWS code removed. Lambda path remains fully functional.

---

## Introduction

This document formalises the requirements derived from the approved design (`design.md`) for adding Azure Container Apps (ACA) as a supported deployment target alongside the existing AWS Lambda deployment. Each requirement maps back to a specific section of the design. Acceptance criteria are written in EARS format (Easy Approach to Requirements Syntax) so they are individually testable.

The spec preserves the existing AWS Lambda + Bedrock delivery path with runtime behaviour unchanged. The compiled bytecode target drops from Java 25 to Java 21 as part of the approved toolchain alignment (see Requirement 0), so artifacts are not literally byte-identical, but no Lambda-observable behaviour changes. Azure support is activated at runtime exclusively via Spring profiles (`prod,azure,azure-ai`) and independent infrastructure-as-code. A runtime instance activates exactly one cloud path.

---

## Glossary

- **ACA** — Azure Container Apps
- **ACR** — Azure Container Registry
- **AOAI** — Azure OpenAI
- **AMI Role** — Azure Managed Identity role (system-assigned in this spec)
- **LWA** — AWS Lambda Web Adapter (the sidecar removed from Azure images)
- **Primary ChatModel** — The Spring AI `ChatModel` bean selected as primary when multiple Spring AI starters are on the classpath; governed by `spring.ai.model.chat`
- **SWA** — Azure Static Web Apps
- **Target port** — The application port inside a Container App (e.g. 8081 for portfolio-service); ACA ingress maps environment-internal port 80 to this target port
- **Federated credential** — A credential on an Entra ID app registration that lets GitHub Actions authenticate via OIDC without a client secret

---

## Requirement 0: Gradle Toolchain Alignment for Azure-Native Runtime

**User Story:** As a build engineer, I want the project's Gradle toolchain pinned to JDK 21 LTS, so that the Azure Dockerfile can use Microsoft's native `openjdk:21-mariner` image without a custom jlink JRE while preserving the AWS Lambda path's runtime behaviour (see Requirement 13 for the full preservation contract).

**Maps to design:** §3.7 (Dockerfile.azure), §8 (summary), `TODO.md` §1

### Acceptance Criteria

1. WHERE the root `build.gradle` declares the subprojects' Java toolchain THE `languageVersion` SHALL be `JavaLanguageVersion.of(21)`.
2. WHEN `./gradlew :<service>:bootJar` runs for any of the four services THEN the build SHALL succeed against the JDK 21 toolchain and produce a fat JAR containing Java 21 bytecode (class-file major version 65).
3. WHERE any `.java` source file exists THE file SHALL NOT use Java 22+ language or API features (string templates, scoped values, structured concurrency, unnamed variables, class-file API, stream gatherers).
4. WHERE the existing AWS Dockerfiles reference `amazoncorretto:25`, `--multi-release 25`, or `/usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts` THE references SHALL remain unchanged by this spec AND the AWS build SHALL continue to produce working Lambda container images (Corretto 25 compiling to JDK 21 bytecode is a valid configuration).
5. WHERE the repository root contains a spec directory for this feature THE directory SHALL contain a `TODO.md` file documenting the toolchain change, AWS Dockerfile tripwires (`amazoncorretto:25` references, `--multi-release 25` flag, hardcoded `java-25-amazon-corretto` cacerts path), and the recommended future cleanup plan.

---

## Requirement 1: Dual-Cloud Profile Mutual Exclusion

**User Story:** As a platform operator, I want the runtime to fail fast when both `bedrock` and `azure-ai` profiles are active simultaneously, so that misconfiguration surfaces at startup rather than producing ambiguous bean-wiring errors or silent data-path drift.

**Maps to design:** §2.2, §2.5, §4 (P1)

### Acceptance Criteria

1. WHEN `SPRING_PROFILES_ACTIVE` contains both `bedrock` AND `azure-ai` THEN the Spring application context SHALL fail to start with an `IllegalStateException` whose message identifies the conflicting profiles.
2. WHEN `SPRING_PROFILES_ACTIVE` contains exactly one of `{bedrock, azure-ai}` OR neither THEN startup SHALL succeed and exactly one `AiInsightService` bean and exactly one `InsightAdvisor` bean SHALL be registered.
3. WHERE the Terraform Azure root defines a Container App THE `SPRING_PROFILES_ACTIVE` environment variable SHALL contain at most one of `{bedrock, azure-ai}`.
4. IF a `terraform plan` would provision a Container App whose `SPRING_PROFILES_ACTIVE` contains both `bedrock` and `azure-ai` THEN the plan-assertion script SHALL exit non-zero.

---

## Requirement 2: Reactive JWT Decoder Availability on Azure

**User Story:** As an API gateway operator, I want a `ReactiveJwtDecoder` bean registered under the `azure` profile, so that HS256-signed tokens issued by `AuthController` continue to validate when the gateway runs on Azure Container Apps.

**Maps to design:** §3.1 (G1), §4 (P2)

### Acceptance Criteria

1. WHERE the `JwtDecoderConfig.hmacJwtDecoder` method is annotated with `@Profile` THE annotation value SHALL be `{"local", "aws", "azure"}`.
2. WHEN the Spring context boots under any one of profiles `local`, `aws`, `azure` THEN exactly one `ReactiveJwtDecoder` bean SHALL be registered.
3. WHEN `PreservationPropertyTest.jwtDecoderConfigHasCorrectProfileAnnotations` runs THEN it SHALL assert the profile array contains exactly `local`, `aws`, and `azure` (order-independent).
4. WHEN `SecurityConfig.springSecurityFilterChain` is wired under the `azure` profile THEN the `oauth2ResourceServer().jwt()` configuration SHALL resolve the decoder bean without a `NoSuchBeanDefinitionException`.
5. WHEN the secret provided via `auth.jwt.secret` is shorter than 32 bytes under any of `{local, aws, azure}` THEN bean construction SHALL throw `IllegalStateException` with a message naming all three profiles.

---

## Requirement 3: AI Adapter Profile Isolation

**User Story:** As the insight-service maintainer, I want the Mock, Bedrock, and Azure OpenAI adapter classes to be mutually exclusive at runtime, so that the service never silently falls back to mocks on production or produces `NoUniqueBeanDefinitionException`.

**Maps to design:** §3.2 (G2), §3.5 (new adapters), §4 (P3)

### Acceptance Criteria

1. WHERE `MockAiInsightService` is declared THE `@Profile` expression SHALL be `!bedrock & !azure-ai`.
2. WHERE `MockInsightAdvisor` is declared THE `@Profile` expression SHALL be `!bedrock & !azure-ai`.
3. WHERE `AzureOpenAiInsightService` is declared THE `@Profile` expression SHALL be `azure-ai` AND the class SHALL implement `com.wealth.insight.AiInsightService`.
4. WHERE `AzureOpenAiInsightAdvisor` is declared THE `@Profile` expression SHALL be `azure-ai` AND the class SHALL implement `com.wealth.insight.advisor.InsightAdvisor`.
5. WHERE `BedrockAiInsightService` and `BedrockInsightAdvisor` are declared THE `@Profile` expression SHALL remain `bedrock` (unchanged).
6. WHEN the Spring context boots under `local` THEN exactly one `AiInsightService` bean SHALL be the Mock variant AND exactly one `InsightAdvisor` bean SHALL be the Mock variant.
7. WHEN the Spring context boots with `bedrock` active THEN exactly one `AiInsightService` bean SHALL be the Bedrock variant AND exactly one `InsightAdvisor` bean SHALL be the Bedrock variant.
8. WHEN the Spring context boots with `azure-ai` active THEN exactly one `AiInsightService` bean SHALL be the Azure variant AND exactly one `InsightAdvisor` bean SHALL be the Azure variant.

---

## Requirement 4: Spring AI ChatModel Primary Selection

**User Story:** As an insight-service maintainer, I want the Spring AI `ChatModel` primary bean to match the active AI profile, so that adding the Azure OpenAI starter to the classpath does not break the Bedrock path (or vice-versa).

**Maps to design:** §3.4 (G3), §4 (P4)

### Acceptance Criteria

1. WHERE `insight-service/build.gradle` declares Spring AI starters THE dependency list SHALL include BOTH `org.springframework.ai:spring-ai-starter-model-bedrock-converse` AND `org.springframework.ai:spring-ai-starter-model-azure-openai` — both resolved via the existing `spring-ai-bom:2.0.0-M4` — plus an explicit `com.azure:azure-identity` dependency (pinned directly to `1.13.2` unless superseded by a managing BOM available in the workspace).
2. WHERE `application-bedrock.yml` is active THE `spring.ai.model.chat` property SHALL be set to `bedrock-converse`.
3. WHERE `application-azure-ai.yml` is active THE `spring.ai.model.chat` property SHALL be set to `azure-openai`.
4. WHEN the Spring context boots under `bedrock` with both starters on the classpath THEN the primary `ChatModel` bean SHALL be the Bedrock-backed implementation AND the injected `ChatClient.Builder` in `BedrockAiInsightService` / `BedrockInsightAdvisor` SHALL resolve without ambiguity.
5. WHEN the Spring context boots under `azure-ai` with both starters on the classpath THEN the primary `ChatModel` bean SHALL be the Azure-OpenAI-backed implementation AND the injected `ChatClient.Builder` in `AzureOpenAiInsightService` / `AzureOpenAiInsightAdvisor` SHALL resolve without ambiguity.

---

## Requirement 5: Azure OpenAI Authentication Mode

**User Story:** As the insight-service operator, I want Azure OpenAI authentication to use System-Assigned Managed Identity by default, so that no API key material lives in source, Container App secrets, or GitHub Actions secrets.

**Maps to design:** §3.4 (application-azure-ai.yml), §3.8 (Terraform role assignment), §2.3

### Acceptance Criteria

1. WHERE `application-azure-ai.yml` declares `spring.ai.azure.openai.endpoint` THE `api-key` property SHALL remain commented out so that `DefaultAzureCredential` resolves via Managed Identity.
2. WHERE the Azure Terraform root declares the insight-service Container App THE `identity { type = "SystemAssigned" }` block SHALL be present.
3. WHERE the Azure Terraform root declares an `azurerm_cognitive_account` of kind `OpenAI` THE plan SHALL also declare an `azurerm_role_assignment` with `role_definition_name = "Cognitive Services OpenAI User"` whose `principal_id` matches the insight-service Container App's system-assigned identity principal.
4. IF the operator chooses API-key auth instead THEN the design's documented switch SHALL be followed verbatim: uncomment `api-key: ${AZURE_OPENAI_API_KEY}` in `application-azure-ai.yml`, add `AZURE_OPENAI_API_KEY` as a Container App secret, and remove the `Cognitive Services OpenAI User` role assignment from Terraform.
5. WHERE API-key auth is NOT chosen THE Terraform plan SHALL NOT declare an `AZURE_OPENAI_API_KEY` secret on the insight-service Container App.

---

## Requirement 6: ACR Image Pull Authorization

**User Story:** As a platform operator, I want every Container App to pull images from ACR via its system-assigned managed identity, so that no admin credentials are stored and revision activation succeeds on first deploy.

**Maps to design:** §3.8 (Terraform `container-app` module), §2.3, §4 (P5)

### Acceptance Criteria

1. WHERE the Azure Terraform root declares a Container App THE resource SHALL include `identity { type = "SystemAssigned" }`.
2. WHERE the Azure Terraform root declares a Container App THE resource SHALL include `registry { server = <acr-login-server>, identity = "system" }`.
3. WHERE the Azure Terraform root declares a Container App THE plan SHALL also declare a `azurerm_role_assignment` with `role_definition_name = "AcrPull"`, `scope = <acr-resource-id>`, and `principal_id` matching the Container App's system-assigned identity principal.
4. WHERE the Azure Terraform root declares `azurerm_container_registry` THE `admin_enabled` argument SHALL be `false`.
5. WHEN `terraform plan` is run against the Azure root THEN the plan-assertion script SHALL verify that every `azurerm_container_app` resource has a corresponding `AcrPull` assignment and fail the plan step otherwise.

---

## Requirement 7: Service-to-Service URL Wiring on Azure

**User Story:** As an api-gateway operator on Azure, I want downstream service URLs to use ACA internal DNS without explicit ports, so that the environment's ingress correctly maps port 80 to each service's target port.

**Maps to design:** §3.10, §2.3

### Acceptance Criteria

1. WHERE the api-gateway Container App declares downstream service URLs THE values SHALL be `http://portfolio-service`, `http://market-data-service`, and `http://insight-service` with no port segment.
2. WHERE a downstream Container App declares ingress THE `ingress.target_port` SHALL match that service's Spring Boot `server.port` (8081 for portfolio-service, 8082 for market-data-service, 8083 for insight-service).
3. WHERE a downstream Container App declares ingress THE `ingress.external_enabled` SHALL be `false`.
4. WHERE the api-gateway Container App declares ingress THE `ingress.external_enabled` SHALL be `true` AND `target_port` SHALL be `8080`.
5. WHEN the Azure environment is freshly created AND the first revision is activating THEN the deploy workflow SHALL poll for `provisioningState=Succeeded` before running smoke tests.

---

## Requirement 8: Per-Service Azure Spring Profiles

**User Story:** As a Spring Boot maintainer, I want an `application-azure.yml` per service that mirrors the `application-aws.yml` pattern, so that cloud-specific overrides are isolated and mutually exclusive.

**Maps to design:** §3.6, §2.5

### Acceptance Criteria

1. WHERE the repository contains `api-gateway/src/main/resources/` THE directory SHALL contain a file `application-azure.yml` with `management.health.redis.enabled: false` AND a configurable `app.cors.allowed-origin-patterns` defaulting to an Azure Static Web Apps hostname pattern.
2. WHERE the repository contains `portfolio-service/src/main/resources/` THE directory SHALL contain a file `application-azure.yml` with an `fx.azure.rates-url` FX endpoint AND `spring.cache.type: simple`.
3. WHERE the repository contains `market-data-service/src/main/resources/` THE directory SHALL contain a file `application-azure.yml` with `market-data.refresh.enabled: true` (ACA long-lived containers) AND `management.health.mongodb.enabled: true`.
4. WHERE the repository contains `insight-service/src/main/resources/` THE directory SHALL contain a file `application-azure.yml` with `management.health.redis.enabled: false`.
5. WHERE any `application-azure.yml` file exists THE file SHALL contain no `localhost` references.
6. WHERE the repository contains `insight-service/src/main/resources/` THE directory SHALL contain a file `application-azure-ai.yml` matching the shape in design §3.4.

---

## Requirement 9: Azure-Specific Dockerfiles with Native Optimization

**User Story:** As a build engineer, I want a separate `Dockerfile.azure` per service using a Microsoft-native Mariner OpenJDK 21 base for both builder and runtime, so that Azure images have no Lambda sidecar, no custom jlink JRE, and a single toolchain end-to-end.

**Maps to design:** §3.7 (updated per design review + toolchain alignment)

### Acceptance Criteria

1. WHERE the repository contains `<service>/Dockerfile` for each of `{api-gateway, portfolio-service, market-data-service, insight-service}` THE directory SHALL also contain `<service>/Dockerfile.azure`.
2. WHERE `<service>/Dockerfile.azure` exists THE file SHALL declare exactly two build stages (`builder` and `runtime`) — no `gradle-dist` stage, no `jre-builder` stage, no custom jlink step.
3. WHERE `<service>/Dockerfile.azure` declares the builder stage THE stage SHALL begin with `FROM mcr.microsoft.com/openjdk/jdk:21-mariner AS builder`.
4. WHERE `<service>/Dockerfile.azure` declares the runtime stage THE stage SHALL begin with `ARG RUNTIME_BASE=mcr.microsoft.com/openjdk/jdk:21-mariner` followed by `FROM ${RUNTIME_BASE} AS runtime`.
5. WHERE `<service>/Dockerfile.azure` declares the runtime stage THE stage SHALL NOT contain `COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter` directives, SHALL NOT contain `COPY --from=jre-builder` directives, and SHALL NOT set `JAVA_HOME` or `PATH` overrides.
6. WHERE `api-gateway/Dockerfile.azure` declares the runtime stage THE stage SHALL NOT set `AWS_LWA_PORT` or `AWS_LWA_READINESS_CHECK_PATH` environment variables.
7. WHERE `<service>/Dockerfile.azure` builds the fat JAR THE builder stage SHALL invoke `./gradlew :<service>:bootJar --no-daemon` against the repo-root Gradle build.
8. WHERE the existing `<service>/Dockerfile` (the AWS variant) exists THE file SHALL NOT be modified by this spec.
9. WHEN an operator runs `docker build --build-arg RUNTIME_BASE=<alternate-image> -f <service>/Dockerfile.azure .` THEN the build SHALL succeed against any Mariner-based or Debian/Alpine glibc-compatible runtime image that ships JDK 21 or later.
10. WHEN `docker build -f <service>/Dockerfile.azure .` runs with no overrides THEN the resulting image SHALL launch Spring Boot as PID 1 via `ENTRYPOINT ["java", "-jar", "/app/app.jar"]` and expose the service's canonical port (8080 / 8081 / 8082 / 8083).

---

## Requirement 10: Separate Terraform Roots per Cloud

**User Story:** As an IaC maintainer, I want the existing Terraform root relocated under `infrastructure/terraform/aws/` and a new `infrastructure/terraform/azure/` root alongside it, so that each cloud has isolated state, providers, and CI jobs.

**Maps to design:** §3.8

### Acceptance Criteria

1. WHERE the repository contains `infrastructure/terraform/` THE directory SHALL contain a subdirectory `aws/` housing all existing root-level Terraform files and the `modules/` subtree.
2. WHERE the repository contains `infrastructure/terraform/` THE directory SHALL contain a subdirectory `azure/` housing `main.tf`, `variables.tf`, `outputs.tf`, `providers.tf`, `versions.tf`, `backend-azure.hcl`, and a `modules/container-app/` subtree.
3. WHERE the existing `infrastructure/terraform/aws/` providers are declared THE provider configuration SHALL be unchanged from the pre-relocation root; only the file location changes.
4. WHERE the new `infrastructure/terraform/azure/providers.tf` declares the `azurerm` provider THE provider SHALL set `use_oidc = true` AND SHALL NOT include a `client_secret` reference.
5. WHEN an operator runs `terraform init` in either root THEN initialization SHALL succeed without requiring credentials for the other cloud.
6. WHEN `terraform validate` runs in `infrastructure/terraform/azure/` THEN it SHALL exit zero.

---

## Requirement 11: Azure Resource Provisioning

**User Story:** As a platform operator, I want the Azure Terraform root to provision a minimal but complete set of resources to run all four services at a cost floor of ~$5–$6/month (scale-to-zero) or ~$7–$9/month with a warm api-gateway replica.

**Maps to design:** §3.8, §2.3, §2.1 (region)

### Acceptance Criteria

1. WHERE the Azure Terraform root declares a resource group THE `location` SHALL be `centralindia` to collocate with existing data-tier providers (Aiven, Neon, Atlas, Upstash) and mitigate cross-cloud RTT (G4, G5).
2. WHERE the Azure Terraform root declares `azurerm_container_registry` THE `sku` SHALL be `Basic`.
3. WHERE the Azure Terraform root declares `azurerm_container_app_environment` THE environment SHALL be associated with an `azurerm_log_analytics_workspace` of sku `PerGB2018` and retention `30` days.
4. WHERE the Azure Terraform root declares Container Apps THE plan SHALL declare exactly four: `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`.
5. WHERE the Azure Terraform root declares a Container App THE default `min_replicas` SHALL be `0` AND `max_replicas` SHALL be `3` or less.
6. WHERE the Azure Terraform root declares the api-gateway Container App THE `min_replicas` SHALL be driven by a variable `api_gateway_min_replicas` defaulting to `0`, with `1` as a supported override.
7. WHERE the Azure Terraform root declares an Azure OpenAI resource THE `sku_name` SHALL be `S0` AND a deployment for `gpt-4o-mini` (OpenAI format, version `2024-07-18`, Standard SKU) SHALL be declared.
8. WHERE the Azure Terraform root declares frontend hosting THE `azurerm_static_web_app` SHALL use `sku_tier = "Free"`.
9. WHERE a Container App declares resource sizing THE default SHALL be `cpu = 0.5` and `memory = "1Gi"`.

---

## Requirement 12: OIDC-Based CI/CD Workflows

**User Story:** As a release engineer, I want GitHub Actions workflows that deploy to Azure without storing client secrets, so that credential rotation is automatic and out-of-band rotation of federated credentials is the only operational requirement.

**Maps to design:** §3.9, §5.2

### Acceptance Criteria

1. WHERE the repository contains `.github/workflows/` THE directory SHALL contain a file `deploy-azure.yml`.
2. WHERE the repository contains `.github/workflows/` THE directory SHALL contain a file `terraform-azure.yml`.
3. WHERE `deploy-azure.yml` declares a job THE job SHALL set `permissions.id-token: write` and `permissions.contents: read`.
4. WHERE `terraform-azure.yml` declares a job THE job SHALL set `permissions.id-token: write` and `permissions.contents: read`.
5. WHERE either Azure workflow authenticates to Azure THE step SHALL use `azure/login@v2` with `client-id`, `tenant-id`, and `subscription-id` inputs AND SHALL NOT supply a `client-secret` input.
6. WHERE `deploy-azure.yml` builds images THE build SHALL use `Dockerfile.azure` (not `Dockerfile`) for each service.
7. WHERE `deploy-azure.yml` updates Container Apps THE `az containerapp update --image` call SHALL target the image pushed in the same workflow run (same tag).
8. WHERE the existing `.github/workflows/deploy.yml` and `.github/workflows/terraform.yml` exist THE files SHALL NOT be modified by this spec.

---

## Requirement 13: AWS Path Preservation

**User Story:** As an AWS Lambda user, I want every existing Lambda behaviour preserved, so that adding Azure support introduces zero Lambda-observable risk to the currently-running production deployment. Artifacts are not literally byte-identical (the Java 25 → 21 bytecode target drop applies to both paths per Requirement 0), but no runtime behaviour changes on the AWS side.

**Maps to design:** §8, §5.2, "Constraint" in the Introduction

### Acceptance Criteria

1. WHERE any of `application.yml`, `application-prod.yml`, `application-local.yml` exists in any module THE file SHALL NOT be modified by this spec.
2. WHERE the root `build.gradle` is modified THE only permitted modification SHALL be the single-line toolchain change in Requirement 0 (`JavaLanguageVersion.of(25)` → `JavaLanguageVersion.of(21)`).
3. WHERE `application-aws.yml` exists in any module THE file SHALL NOT be modified by this spec.
4. WHERE `application-bedrock.yml` exists THE only modification SHALL be the addition of `spring.ai.model.chat: bedrock-converse` under `spring.ai`, with no other key changes.
5. WHERE `<service>/Dockerfile` exists for each of the four services THE file SHALL NOT be modified by this spec (AWS cleanup is tracked as backlog B2 in `design.md` §6 and `TODO.md` §1.3).
6. WHERE the CDK stacks under `infrastructure/lib/*.ts` exist THE files SHALL NOT be modified by this spec.
7. WHERE the AWS Terraform modules under `infrastructure/terraform/aws/modules/**` exist THE files SHALL NOT be modified in their content; only their directory path changes (from `infrastructure/terraform/modules/` to `infrastructure/terraform/aws/modules/`).
8. WHERE `.github/workflows/deploy.yml` and `.github/workflows/terraform.yml` exist THE files SHALL NOT be modified by this spec.
9. WHEN the Bedrock profile is active with the new Azure OpenAI starter on the classpath THEN the Bedrock code path SHALL produce identical sentiment output to the pre-spec behaviour for any given ticker and market data input.
10. WHEN the existing PreservationPropertyTest suite runs against the modified gateway code under `ActiveProfiles("local")` THEN all tests SHALL pass.
11. WHEN the AWS Lambda path is built and deployed after the toolchain downgrade THEN runtime behaviour SHALL be identical to pre-spec behaviour (Corretto 25 builder producing Java 21 bytecode is a valid configuration; Java 21 bytecode runs unchanged on the Java 25 jlink JRE in the existing runtime image).

---

## Requirement 14: Observability Parity on Insight-Service

**User Story:** As an insight-service operator, I want `[INFRA-OK]` / `[INFRA-FAIL]` startup log lines to appear on Azure deployments, so that infrastructure connectivity can be diagnosed in Log Analytics the same way it is in CloudWatch.

**Maps to design:** §3.3 (G17)

### Acceptance Criteria

1. WHERE `insight-service/src/main/java/com/wealth/insight/InfrastructureHealthLogger.java` is declared THE `@Profile` annotation value SHALL be `{"aws", "azure"}`.
2. WHEN the insight-service Spring context reaches `ApplicationReadyEvent` under the `azure` profile THEN exactly one `[INFRA-OK]` or `[INFRA-FAIL]` log line SHALL be emitted per configured dependency.
3. WHERE the api-gateway copy of `InfrastructureHealthLogger` exists THE file SHALL NOT be modified by this spec (see backlog B1 in design §6).

---

## Requirement 15: Correctness Properties as Executable Tests

**User Story:** As a reviewer, I want each design-declared correctness property (P1–P5) backed by an automated test, so that regressions in profile wiring, classpath disambiguation, or IaC role assignment are caught in CI.

**Maps to design:** §4

### Acceptance Criteria

1. WHERE the insight-service test source tree exists THE tree SHALL contain a jqwik property test that verifies P1 by enumerating profile combinations over `{local, prod, aws, azure, bedrock, azure-ai}` and asserting startup success or `IllegalStateException` per the rule in Requirement 1.
2. WHERE the api-gateway test source tree exists THE tree SHALL contain tests verifying P2: under each of profiles `{local, aws, azure}`, exactly one `ReactiveJwtDecoder` bean is registered.
3. WHERE the insight-service test source tree exists THE tree SHALL contain tests verifying P3: under each of `{local, bedrock, azure-ai}`, exactly one `AiInsightService` and one `InsightAdvisor` bean is registered.
4. WHERE the insight-service test source tree exists THE tree SHALL contain a test verifying P4: with both Spring AI starters on the classpath, the primary `ChatModel` bean matches the `spring.ai.model.chat` property value.
5. WHERE `infrastructure/terraform/azure/scripts/` exists THE directory SHALL contain a Python script verifying P5: `terraform show -json tfplan` output confirms every `azurerm_container_app` has matching `AcrPull` and identity configuration; missing assignment fails the plan step.
6. WHEN the `test` and `integrationTest` Gradle tasks run THEN all P1–P4 property tests SHALL pass.
7. WHEN the Terraform plan step runs in `terraform-azure.yml` THEN the P5 assertion script SHALL pass.

---

## Requirement 16: Cosmetic Cleanup During Implementation

**User Story:** As a code-cleanliness reviewer, I want the stale Ollama javadoc reference cleaned up in the Bedrock property test, so that the codebase reflects the removed integration accurately.

**Maps to design:** §7

### Acceptance Criteria

1. WHERE `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/BedrockAiInsightServicePropertyTest.java` exists THE class-level javadoc SHALL NOT reference the removed `OllamaAiInsightServicePropertyTest`.

---

## Requirement 17: Pre-Deployment Operational Prerequisites Documented

**User Story:** As an operator preparing for the first Azure deployment, I want the manual Entra ID setup and GitHub Actions secret list documented in the design, so that the secret-less workflow can run end-to-end on first attempt.

**Maps to design:** §5.2

### Acceptance Criteria

1. WHERE the design document describes deployment prerequisites THE prerequisites SHALL list: Entra app registration; federated credentials for `refs/heads/main` and optionally `pull_request`; role assignments `Contributor` + `User Access Administrator` + `AcrPush`; GitHub secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`.
2. WHERE the prerequisites list is documented THE list SHALL explicitly state that no `AZURE_CLIENT_SECRET` is required or used.
3. IF any of these prerequisites is missing at workflow run time THEN the failure mode (`AADSTS70021: No matching federated identity record found`) SHALL be documented as the expected symptom.

*(This requirement is satisfied by the design's §5.2 itself — its inclusion here ensures the prerequisite list travels with the feature through the tasks phase.)*

---

## Out-of-Scope (Tracked in design §6 Backlog and `TODO.md`)

- **B1:** Widening `api-gateway/InfrastructureHealthLogger` `@Profile` to `{"aws","azure"}` for observability parity on api-gateway (analogous to Requirement 14 for insight-service).
- **B2:** Aligning the four AWS Dockerfiles from `amazoncorretto:25` → `amazoncorretto:21`, fixing `--multi-release` and cacerts paths. Full tripwire table in `TODO.md` §1.3.
- **B3:** Evaluating migration from custom Lambda container runtime to the managed `java21` runtime (architectural change; see `TODO.md` §1.4).
- **B4:** Per-cloud Kafka consumer group IDs to support simultaneous dual-cloud consumption for A/B validation.
- **B5:** mTLS between ACA internal services via Dapr or a service mesh add-on.

These are deliberately deferred and must not be implemented as part of this spec.
