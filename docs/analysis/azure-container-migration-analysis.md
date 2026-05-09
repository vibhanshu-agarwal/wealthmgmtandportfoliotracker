# Azure Container Migration Analysis
## AWS Lambda → Azure Container Apps (Dual-Cloud Strategy)

**Date:** May 1, 2026
**Status:** Analysis Only — No Code Changes Made
**Last revision:** May 2, 2026 — incorporates findings from a comprehensive technical validation pass (profile-guard blockers, Spring AI classpath collision, separate Terraform roots, OIDC CI auth, corrected cost projection, realistic cold-start envelope).
**Branch:** Pending approval → `migration/azure-cloud` (worktree)
**Scope:** All four Spring Boot services: `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`. Stack: Java 25 / Spring Boot 4.0.5 / Spring AI 2.0.0-M4 (under review per §9.2).

---

## Executive Summary

This report analyses what is required to add Azure Container Apps (ACA) as a supported deployment target alongside the existing AWS Lambda deployment. The goal is a **dual-cloud architecture** where both targets are supported simultaneously via Spring profile switching — no AWS code is removed.

The codebase is well-positioned at the domain and service layers (no AWS SDK in business logic), but a validation pass surfaced four code-level concerns that the original draft missed and that this revision now addresses explicitly:

1. `JwtDecoderConfig` is profile-bound to `local`/`aws` and must be widened to include `azure` (§5.3.1, G1).
2. `MockAiInsightService` and `MockInsightAdvisor` are guarded with `@Profile("!bedrock")` and would silently activate alongside the new Azure adapter; their guards must widen to `!bedrock & !azure-ai` (§5.3.2, G2).
3. The Bedrock and Azure OpenAI Spring AI starters both contribute `ChatModel` beans by `@ConditionalOnClass` — Spring profiles do not disambiguate auto-configuration. The fix is `spring.ai.model.chat` per profile (§9.1, G3).
4. ACA Container Apps need `AcrPull` role assignments on their system-assigned identities to pull from ACR; this is not implicit (§6.1, G6).

The bulk of the work remains infrastructure (separate Terraform root for Azure under `infrastructure/terraform/azure/`) and new Spring profiles (`application-azure.yml` per service). Application-layer changes are contained to four small `@Profile` annotation edits and one new pair of AI-adapter classes.

**Estimated monthly Azure cost:** ~$5–$6/month under strict scale-to-zero, or ~$7–$9/month if `minReplicas: 1` is set on `api-gateway` to keep one warm instance (idle billing exceeds the ACA Consumption free grant once a replica runs 24×7). ACR Basic dominates the floor; using GHCR instead reduces the floor to ~$0–$1/month. ACR Basic is **not** part of Azure's 12-month free service list.

---

## 1. Current Architecture (AWS)

```
Browser
  └─► Route 53 → CloudFront (CDN + TLS)
        ├─► S3 (Next.js static export)
        └─► api-gateway Lambda (Function URL, arm64, 2048 MB)
              ├─► portfolio-service Lambda (Function URL)
              ├─► market-data-service Lambda (Function URL)
              └─► insight-service Lambda (Function URL → Amazon Bedrock Claude Haiku 4.5)

External managed services (shared across clouds):
  - Neon PostgreSQL (portfolio-service)
  - MongoDB Atlas (market-data-service)
  - Aiven Kafka (portfolio-service, market-data-service, insight-service)
  - Upstash Redis (api-gateway rate limiting, insight-service cache)
```

**IaC:** Terraform (`infrastructure/terraform/`) — single source of truth.  
**CI/CD:** GitHub Actions (`deploy.yml` for images, `terraform.yml` for infra).  
**Container images:** Multi-stage Dockerfiles, Amazon Linux 2023 Minimal base, custom `jlink` JRE, Lambda Web Adapter sidecar at `/opt/extensions/lambda-adapter`.

---

## 2. Target Architecture (Azure)

```
Browser
  └─► Azure Front Door (CDN + TLS) OR Azure Static Web Apps (frontend)
        └─► api-gateway Container App (ACA Consumption plan)
              ├─► portfolio-service Container App
              ├─► market-data-service Container App
              └─► insight-service Container App → Azure OpenAI (GPT-4o-mini) OR
                                                   Azure AI Foundry (Claude via Anthropic)

Container registry: Azure Container Registry (ACR) Basic tier
IaC: New Terraform module OR Bicep (additive, does not touch existing AWS modules)
```

External managed services remain **unchanged** — Neon, MongoDB Atlas, Aiven Kafka, and Upstash Redis all work from Azure without modification.

---

## 3. What Changes vs. What Stays the Same

### 3.1 What Stays the Same (No Changes Required)

| Component | Reason |
|---|---|
| All Java/Spring Boot application code | No AWS SDK in domain/service layers; hexagonal architecture holds |
| All four Dockerfiles (build stages 0–2) | Gradle build, jdeps/jlink JRE stages are cloud-agnostic |
| `application.yml` (base config) | Already uses env-var substitution for all URLs and secrets |
| `application-prod.yml` (per service) | Cloud-agnostic production defaults |
| `application-local.yml` (per service) | Docker Compose local dev unchanged |
| `common-dto` module | Shared Kafka event contracts, no cloud dependency |
| External services (Neon, Atlas, Aiven, Upstash) | All reachable from Azure over the internet |
| Frontend Next.js code | No backend cloud dependency |
| GitHub Actions CI (`ci.yml`, `ci-verification.yml`) | Unit/integration tests are cloud-agnostic |
| Existing AWS Terraform modules | Additive approach — AWS modules untouched |

### 3.2 What Needs to Change / Be Added

| Component | Change Type | Details |
|---|---|---|
| Dockerfiles (Stage 3 runtime) | **Modify** | Remove Lambda Web Adapter (`/opt/extensions/lambda-adapter`); switch base image from `amazonlinux:2023-minimal` to `eclipse-temurin:25-jre-alpine` or `debian:bookworm-slim` |
| `application-aws.yml` (per service) | **No change** | Stays as-is for AWS path |
| `application-azure.yml` (per service) | **Add new** | New profile per service (4 files) |
| `insight-service` AI provider | **Add new** | New `application-azure-ai.yml` profile for Azure OpenAI / AI Foundry |
| `infrastructure/terraform/azure/` (new root) | **Add new** | Separate Terraform root module for the Azure side. Existing root relocates to `infrastructure/terraform/aws/`. Independent state, providers, and CI jobs (§6.3) |
| GitHub Actions `deploy-azure.yml` | **Add new** | New workflow for ACR push + ACA revision update |
| GitHub Actions `terraform-azure.yml` | **Add new** | New workflow for Azure Terraform apply |
| `CloudFrontOriginVerifyFilter` (api-gateway) | **Conditional** | Validates `X-Origin-Verify` header injected by CloudFront. The filter is already a no-op when `CLOUDFRONT_ORIGIN_SECRET` is unset (env-var driven, not property-driven). On Azure simply do not set the env var; an explicit profile guard is optional, not required |
| `JwtDecoderConfig` (api-gateway) | **Modify (blocker)** | The `hmacJwtDecoder` bean is declared `@Profile({"local", "aws"})`. Under `prod,azure` no `ReactiveJwtDecoder` is registered → `SecurityConfig.oauth2ResourceServer().jwt()` cannot validate tokens. Must add `"azure"` to the profile array |
| `MockAiInsightService` / `MockInsightAdvisor` (insight-service) | **Modify (blocker)** | Both are `@Profile("!bedrock")`, so under `prod,azure,azure-ai` they activate alongside any Azure adapter — silent mock responses or duplicate-bean errors. Must widen to `@Profile("!bedrock & !azure-ai")` |
| New Azure AI adapters (insight-service) | **Add new** | New `AzureOpenAiInsightService` / `AzureOpenAiInsightAdvisor` annotated `@Profile("azure-ai")`, mirroring the Bedrock pair |
| `InfrastructureHealthLogger` (insight-service) | **Modify (minor)** | Annotated `@Profile("aws")`; widen to `{"aws", "azure"}` to preserve `[INFRA-OK]`/`[INFRA-FAIL]` startup probes on Azure |

---

## 4. Dockerfile Changes (Per Service)

The current Stage 3 runtime is tightly coupled to AWS Lambda in two ways:

1. **Base image:** `public.ecr.aws/amazonlinux/amazonlinux:2023-minimal` — works fine on Azure but pulls from AWS ECR public. Acceptable to keep, but switching to a neutral base (e.g. `debian:bookworm-slim`) removes the AWS dependency.

2. **Lambda Web Adapter sidecar:** `COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/lambda-adapter` — this is the critical Lambda-specific line. On Azure Container Apps, there is no Lambda runtime; the Spring Boot HTTP server runs directly and ACA routes HTTP traffic to it natively. The LWA binary must be removed from the Azure image.

**Strategy: Dual Dockerfile approach (recommended)**

Rather than a single Dockerfile with build-arg conditionals (which adds complexity), create a parallel set:

```
api-gateway/Dockerfile              ← existing (AWS Lambda, unchanged)
api-gateway/Dockerfile.azure        ← new (Azure Container Apps)
portfolio-service/Dockerfile        ← existing (unchanged)
portfolio-service/Dockerfile.azure  ← new
market-data-service/Dockerfile      ← existing (unchanged)
market-data-service/Dockerfile.azure ← new
insight-service/Dockerfile          ← existing (unchanged)
insight-service/Dockerfile.azure    ← new
```

The `.azure` Dockerfiles are identical to the existing ones **except**:
- Remove the `COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter` line (all four services).
- Remove `ENV AWS_LWA_PORT` and `ENV AWS_LWA_READINESS_CHECK_PATH` — **only present in `api-gateway/Dockerfile`**; the other three Dockerfiles do not set these vars, so the diff there is just the LWA `COPY` line.
- (Optional) Stage 3 base image swap to `debian:bookworm-slim` or `eclipse-temurin:25-jre-alpine`. If swapped, the package-install step must change accordingly: `apt-get install -y ca-certificates` (Debian) or `apk add --no-cache ca-certificates` (Alpine) instead of `microdnf install -y ca-certificates`. The lowest-risk option is to keep `public.ecr.aws/amazonlinux/amazonlinux:2023-minimal` — `public.ecr.aws` is anonymous-pullable from Azure.
- Keep `EXPOSE` and `ENTRYPOINT` unchanged — Spring Boot runs on the same port natively under ACA.

**Estimated diff per Dockerfile:** 1–5 lines depending on the service (api-gateway: ~5 lines removed; the other three: ~1 line removed). Optional base-image swap adds another 2–3 lines.

---

## 5. Spring Profile Changes (Per Service)

### 5.1 New `application-azure.yml` files (4 files, one per service)

These mirror the pattern already established by `application-aws.yml`. They activate when `SPRING_PROFILES_ACTIVE=prod,azure`.

**`api-gateway/src/main/resources/application-azure.yml`** (new)
```yaml
# Azure profile — overrides for Azure Container Apps deployment.
# Active when SPRING_PROFILES_ACTIVE includes "azure" (e.g. "prod,azure").

management:
  health:
    redis:
      enabled: false  # Same rationale as AWS: suppress Redis health on cold start

app:
  cors:
    allowed-origin-patterns: "https://<your-azure-domain>.azurestaticapps.net"

# CloudFront origin-verify header is AWS-specific; disable on Azure.
# The CloudFrontOriginVerifyFilter must check this property before enforcing.
cloudfront:
  origin-verify:
    enabled: false
```

**`portfolio-service/src/main/resources/application-azure.yml`** (new)
```yaml
# Azure profile — overrides for Azure Container Apps deployment.
fx:
  azure:
    rates-url: https://open.er-api.com/v6/latest/USD  # same free FX endpoint
    refresh-cron: "0 0 6 * * *"

spring:
  cache:
    type: simple  # Caffeine in-memory; same as AWS profile (no ElastiCache dependency)
```

**`market-data-service/src/main/resources/application-azure.yml`** (new)
```yaml
# Azure profile — overrides for Azure Container Apps deployment.
market:
  seed:
    enabled: false

market-data:
  refresh:
    enabled: true   # ACA containers are long-lived; scheduled refresh CAN be enabled
  hydration:
    enabled: true
  baseline-seed:
    enabled: false
```

**`insight-service/src/main/resources/application-azure.yml`** (new)
```yaml
# Azure profile — overrides for Azure Container Apps deployment.
# AI configuration lives in application-azure-ai.yml; this file holds non-AI overrides only.
management:
  health:
    redis:
      enabled: false  # same rationale as AWS profile
```

### 5.2 New `application-azure-ai.yml` for insight-service (1 file)

The current `application-bedrock.yml` uses `spring-ai-starter-model-bedrock-converse`. On Azure, the equivalent is `spring-ai-starter-model-azure-openai`. This requires:

1. Adding `spring-ai-starter-model-azure-openai` to `insight-service/build.gradle`. Both starters end up on the classpath simultaneously — see §9 for the auto-configuration disambiguation strategy (this is **not** controlled by Spring profiles).
2. A new profile file that configures the Azure OpenAI endpoint and selects the active chat model.

```yaml
# application-azure-ai.yml — AI adapter for Azure OpenAI / AI Foundry
# Active when SPRING_PROFILES_ACTIVE includes "azure-ai" (e.g. "prod,azure,azure-ai").

spring:
  ai:
    # Spring AI 1.0+ chat-model selector. Forces the Azure OpenAI ChatModel
    # to win over the Bedrock ChatModel that the bedrock-converse starter
    # also contributes when both starters are on the classpath.
    model:
      chat: azure-openai
    azure:
      openai:
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        # api-key is intentionally omitted here. Authentication mode is one of:
        #   (a) API key — set AZURE_OPENAI_API_KEY as a Container App secret and
        #       add `api-key: ${AZURE_OPENAI_API_KEY}` to this file, OR
        #   (b) Managed Identity — leave api-key unset, add the
        #       `com.azure:azure-identity` runtime dependency to build.gradle,
        #       and grant the ACA system-assigned identity the
        #       "Cognitive Services OpenAI User" role on the Azure OpenAI resource.
        # Pick exactly one mode; do not configure both. See §9.
        chat:
          options:
            deployment-name: ${AZURE_OPENAI_DEPLOYMENT:gpt-4o-mini}
            temperature: 0.2
```

**Note on AI model choice for Azure:**
- **Azure OpenAI GPT-4o-mini** — available natively, pay-per-token, ~$0.15/1M input tokens. Lowest cost option.
- **Azure AI Foundry + Anthropic Claude** — Claude family models are available via Azure AI Foundry through the Anthropic partnership. Specific SKUs (e.g. Claude Haiku 4.5) and pricing parity must be verified against the Foundry model catalogue at deployment time, not assumed.
- **Recommendation:** GPT-4o-mini for cost minimisation; Claude via AI Foundry if model parity with AWS is required.

### 5.3 Critical Code-Level Profile Guards (Required)

Three code-level changes are **blockers** for Azure startup and one is a minor observability fix. None of them require touching domain or service-layer code.

#### 5.3.1 `JwtDecoderConfig` — widen profile array (BLOCKER)

The HMAC JWT decoder bean in `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java` is currently `@Profile({"local", "aws"})`. Under `prod,azure` no `ReactiveJwtDecoder` bean is registered, but `SecurityConfig.springSecurityFilterChain` calls `.oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))` which requires one. Result: gateway either fails on startup or returns 401 on every authenticated request.

**Fix:** add `"azure"` to the profile array.

```java
@Bean
@Profile({"local", "aws", "azure"})
ReactiveJwtDecoder hmacJwtDecoder(@Value("${auth.jwt.secret}") String secret) { ... }
```

One-line change.

#### 5.3.2 `MockAiInsightService` / `MockInsightAdvisor` — widen exclusion (BLOCKER)

Both classes in `insight-service/src/main/java/.../infrastructure/ai/` are `@Profile("!bedrock")`. Under `prod,azure,azure-ai` they activate alongside any new Azure adapter, producing either:
- **Silent mock behaviour** (mocks win the bean injection if no Azure adapter is added), or
- **`NoUniqueBeanDefinitionException`** (when both Mock and Azure adapters are present).

**Fix:** widen the exclusion to cover the Azure profile.

```java
@Service
@Profile("!bedrock & !azure-ai")
public class MockAiInsightService implements AiInsightService { ... }

@Service
@Profile("!bedrock & !azure-ai")
public class MockInsightAdvisor implements InsightAdvisor { ... }
```

The new `AzureOpenAiInsightService` and `AzureOpenAiInsightAdvisor` classes are annotated `@Profile("azure-ai")`, mirroring the Bedrock pair. The three profile expressions are now mutually exclusive at runtime.

#### 5.3.3 `InfrastructureHealthLogger` — widen profile (minor)

`insight-service/src/main/java/.../InfrastructureHealthLogger.java` is `@Profile("aws")`. On Azure it does not run, costing the `[INFRA-OK]`/`[INFRA-FAIL]` startup log lines. Widen to `{"aws", "azure"}`.

#### 5.3.4 `CloudFrontOriginVerifyFilter` — env-var driven, no Java change required

The filter reads its secret from `System.getenv("CLOUDFRONT_ORIGIN_SECRET")` and is already a no-op when the variable is unset (see filter source, lines 38–41 and 53–55). On Azure, **simply do not set `CLOUDFRONT_ORIGIN_SECRET`** in the Container App environment — the filter loads, finds no secret, and short-circuits.

An explicit `@ConditionalOnProperty` guard is **optional**, not required, and would only control whether the bean is registered (it would not change runtime behaviour because the env-var read happens in the constructor). If desired for clarity, refactor the filter to read its secret from `@Value("${cloudfront.origin-verify.secret:}")` so a single config source drives behaviour — that is a larger change than the §13 row implies and is not on the critical path.

---

## 6. Infrastructure Changes (Azure Terraform Module)

The Azure infrastructure lives in a **separate Terraform root module** to isolate state, providers, and credentials from the existing AWS root. The two roots share no files and are planned/applied independently.

### 6.1 Resources Required

| Resource | Azure Service | Purpose |
|---|---|---|
| Container Registry | Azure Container Registry (ACR) Basic | Store Docker images (replaces ECR) |
| Container Apps Environment | ACA Consumption plan | Shared environment for all 4 apps |
| Container App: api-gateway | ACA | Routes traffic, JWT auth, rate limiting |
| Container App: portfolio-service | ACA | Portfolio holdings, JPA/PostgreSQL |
| Container App: market-data-service | ACA | Market prices, MongoDB |
| Container App: insight-service | ACA | AI insights, Azure OpenAI |
| Role assignment: `AcrPull` | RBAC | Granted to each Container App's system-assigned identity on the ACR resource — without it, revision activation fails with `UNAUTHORIZED: authentication required` on first image pull |
| Role assignment: `Cognitive Services OpenAI User` | RBAC | Granted to `insight-service` system-assigned identity on the Azure OpenAI resource (only if Managed Identity auth is chosen — see §9) |
| Static Web App (optional) | Azure Static Web Apps | Next.js frontend hosting |
| Key Vault (optional) | Azure Key Vault | Secret management (replaces GitHub Secrets → Lambda env vars) |

### 6.2 ACA Configuration Notes

- **Scale-to-zero:** Enabled by default on Consumption plan. All 4 services can scale to zero when idle.
- **Min replicas:** Set to 0 for cost minimisation. Accept cold-start latency (same trade-off as Lambda). Setting `minReplicas: 1` on `api-gateway` keeps the user-facing path warm but exceeds the ACA free grant — see §11 for the cost impact.
- **Service-to-service communication:** ACA provides internal DNS within an environment. Inside the environment, callers use `http://<app-name>` (e.g. `http://portfolio-service`) on **port 80**; ACA ingress maps that to each app's `targetPort`. The downstream services' Spring Boot ports (8081/8082/8083) are configured as `ingress.targetPort` in Terraform, **not** in the URL. This is a key difference from Lambda where Function URLs were used.
- **Ingress:** Only `api-gateway` needs external ingress. The three downstream services use `ingress.external = false` with `transport = "auto"` (or `"http"`).
- **Image pull authentication:** Each Container App must declare a `registry { server = <acr-login-server>, identity = "system" }` block, and the system-assigned identity must hold `AcrPull` on the ACR. Admin-user auth on ACR is not recommended.
- **CPU/Memory:** Minimum viable: 0.25 vCPU / 0.5 GiB per service. Spring Boot 4 with AOT and custom JRE starts in ~12–18s on 0.5 vCPU / 1 GiB warm-node, ~20–35s on a cold node where the image must be pulled (see §8).
- **Region selection:** Pick an Azure region collocated with the data-tier providers (Aiven Kafka, Neon, MongoDB Atlas, Upstash). The existing deployment uses AWS `ap-south-1`; the matching Azure region is `centralindia`. Running ACA in a far region (e.g. `eastus`) adds 50–200 ms RTT per dependency call and degrades Kafka throughput materially.

### 6.3 Terraform Layout (Separate Root Modules)

The recommended layout splits the existing root into a dedicated `aws/` directory and adds a new `azure/` root alongside it. Each has independent state, independent providers, and independent CI jobs:

```
infrastructure/terraform/
├── aws/                          # existing root, relocated unchanged
│   ├── main.tf
│   ├── ecr.tf
│   ├── providers.tf
│   ├── backend-aws.hcl           # azurerm_storage backend OR existing S3 backend
│   └── modules/                  # cdn, compute, database, warming (existing)
│
└── azure/                        # new root
    ├── main.tf                   # ACR, ACA environment, 4 Container Apps, role assignments
    ├── variables.tf              # image URIs, secrets, service URLs, region
    ├── outputs.tf                # ACA ingress URL, ACR login server
    ├── providers.tf              # azurerm provider, configured via OIDC
    ├── backend-azure.hcl         # Azure Blob Storage backend
    └── modules/
        └── container-app/        # reusable per-service Container App module
```

Rationale:
- Plans against one cloud do not require credentials for the other. A single root with both providers fails if either credential is absent.
- State files are isolated. Mistakes in one cloud's plan cannot drift the other cloud's resources.
- The legacy single-root layout (`azure.tf` + `azure-variables.tf` co-located with AWS files) was rejected during validation because it conflates concerns and complicates `terraform import` recovery.

### 6.4 GitHub Actions Workflows (New Files)

```
.github/workflows/deploy-azure.yml    # Build images → push to ACR → update ACA revisions
.github/workflows/terraform-azure.yml # Terraform plan/apply for Azure infra
```

**Authentication: OIDC federated credentials (required).** The new workflows authenticate to Azure via GitHub OIDC, mirroring the AWS pattern. No client secrets in repo or in GitHub Actions secrets:

1. Create an Entra ID app registration with a service principal in the target tenant.
2. Add a federated identity credential on the app registration scoped to `repo:<owner>/<repo>:ref:refs/heads/main` (and optionally `pull_request` for plan-only runs).
3. Grant the SP the minimum role set:
   - `AcrPush` on the ACR (for `deploy-azure.yml` image push).
   - `Contributor` on the resource group (for `terraform-azure.yml` apply). Use a more restrictive custom role if the platform team objects to `Contributor`.
   - `User Access Administrator` on the resource group **only** if Terraform is to create role assignments itself; otherwise pre-create the `AcrPull` and `Cognitive Services OpenAI User` assignments out of band.
4. The workflow uses `azure/login@v2` with `client-id`, `tenant-id`, `subscription-id` and no `client-secret`.

---

## 7. Service-to-Service URL Wiring

On AWS, service-to-service calls use Lambda Function URLs (public HTTPS). On Azure Container Apps, internal DNS is available within the same environment:

| Service | AWS URL pattern | Azure URL pattern (caller-side) |
|---|---|---|
| portfolio-service | `https://<hash>.lambda-url.ap-south-1.on.aws/` | `http://portfolio-service` (internal, port 80) |
| market-data-service | `https://<hash>.lambda-url.ap-south-1.on.aws/` | `http://market-data-service` (internal, port 80) |
| insight-service | `https://<hash>.lambda-url.ap-south-1.on.aws/` | `http://insight-service` (internal, port 80) |

The `application.yml` already uses `${PORTFOLIO_SERVICE_URL:http://localhost:8081}` — the Azure profile simply sets `PORTFOLIO_SERVICE_URL=http://portfolio-service` as an environment variable. **No Java code changes are required for URL wiring.**

**Important port-mapping detail:** ACA internal DNS resolves the bare app name to the environment's internal ingress, which always listens on **port 80**. Each downstream Container App must declare `ingress.targetPort` in Terraform matching its Spring Boot `server.port` (8081 / 8082 / 8083). The ingress translates port 80 → `targetPort`. Therefore the caller URL is `http://portfolio-service` (no port), **not** `http://portfolio-service:8081`. Spring Boot continues to listen on its native port inside the container; ACA does the mapping.

Internal-environment traffic is HTTP (cleartext). For the personal-portfolio scope this is acceptable; if mTLS between services becomes a requirement, ACA's environment-level Dapr or service mesh add-on must be enabled separately (out of scope here).

---

## 8. Cold Start Considerations

Spring Boot 4 with AOT and a custom `jlink` JRE currently takes ~8–15s cold start on Lambda (2048 MB). On ACA Consumption plan, two cold-start regimes apply:

**Warm node (image already cached on the ACA-managed node):**
- **0.25 vCPU / 0.5 GiB:** ~20–30s (CPU-constrained). Acceptable for a demo/portfolio workload.
- **0.5 vCPU / 1 GiB:** ~12–18s. Recommended minimum for acceptable UX.

**Cold node (first scale-from-zero on a fresh node, or after node recycling):**
- The image must be pulled before the container starts. Image size for these services with the custom JRE and AL2023 minimal base is in the 250–400 MiB range; first-pull latency on ACA Consumption is typically 5–15s.
- **Realistic worst case on 0.5 vCPU / 1 GiB: ~20–35s** (image pull + container start + Spring Boot AOT startup + JVM warm-up).
- ACA does not pin nodes to a Container App; nodes are reclaimed after extended idle, so a "first user after several idle hours" hits the cold-node path even when the warm-node path would otherwise win.

**Scale-to-zero:** Same trade-off as Lambda. ACA supports `minReplicas: 1` to keep one instance running, billed at the idle vCPU/GiB rate (lower than active rate but **not zero**, and **not covered by the ACA free grant** once it runs 24×7 — see §11).

The existing AOT compilation and `jlink` JRE optimisations carry over directly — no additional cold-start work is needed for Azure.

**ACA cold-start mitigation options (documented, not required):**
- Set `minReplicas: 1` on api-gateway to keep the user-facing path warm (cost impact: ~$1.50–$3/month, see §11).
- ACA supports [cold-start reduction via pre-pulled images](https://learn.microsoft.com/en-us/azure/container-apps/cold-start) — node-level caching, no code change needed.
- Trim the runtime image: switching the Stage 3 base from AL2023 minimal (~40 MiB) to `eclipse-temurin:25-jre-alpine` (~80 MiB) usually grows the image; the larger lever is dropping unused jlink modules. Diminishing returns past ~200 MiB.

---

## 9. AI Service Migration (insight-service)

The current `insight-service` uses **Amazon Bedrock + Claude Haiku 4.5** via `spring-ai-starter-model-bedrock-converse`. On Azure, two options exist.

### 9.1 Classpath collision: Bedrock and Azure OpenAI starters together

This is the single most subtle correctness risk in the migration. Both `spring-ai-starter-model-bedrock-converse` and `spring-ai-starter-model-azure-openai` register their auto-configurations via `@ConditionalOnClass`, **not** `@ConditionalOnProfile`. With both starters on the classpath, both `ChatModel` beans (`BedrockProxyChatModel` and `AzureOpenAiChatModel`) are created and the existing `BedrockAiInsightService` / `BedrockInsightAdvisor` constructors that take a `ChatClient.Builder` will throw `NoUniqueBeanDefinitionException` at startup.

Spring profiles **cannot** disambiguate this on their own. Choose one of the following resolution strategies:

**Strategy A (recommended): Spring AI chat-model selector property**

Spring AI 1.0.0-M6+ and 2.0.x expose a `spring.ai.model.chat` property that selects which provider's `ChatModel` is the primary bean. Set it per-profile:

```yaml
# application-bedrock.yml (existing)
spring:
  ai:
    model:
      chat: bedrock-converse

# application-azure-ai.yml (new — already shown in §5.2)
spring:
  ai:
    model:
      chat: azure-openai
```

This is declarative, requires no `build.gradle` changes beyond adding the second starter, and keeps both providers usable from local smoke tests.

**Strategy B: Auto-configuration exclusion per profile**

Exclude the unwanted starter's auto-configuration on the active profile via `spring.autoconfigure.exclude`:

```yaml
# application-azure-ai.yml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.model.bedrock.autoconfigure.BedrockConverseProxyChatAutoConfiguration
```

More brittle (the auto-config class name is an implementation detail that has changed between Spring AI milestones) and harder to test. Use only if Strategy A's property is unavailable in the pinned Spring AI version.

**Strategy C: Gradle source sets / build flavours**

Produce two separate JARs (one with the Bedrock starter, one with the Azure OpenAI starter). Cleanest runtime, but doubles the CI build matrix and complicates local dev. Reject unless Strategies A and B are both unworkable.

**Strategy A is the recommended path.** It must be combined with the `@Profile("azure-ai")` / `@Profile("bedrock")` / `@Profile("!bedrock & !azure-ai")` guards specified in §5.3.2 — the property selects which `ChatModel` is primary, and the profile guards select which adapter `@Service` consumes it.

### 9.2 Spring AI version stability

`insight-service/build.gradle` currently pins `spring-ai-bom:2.0.0-M4`. Milestone releases have API churn between M-versions (artefact renames, autoconfig class moves). Before adding the Azure OpenAI starter, either pin both starters to the same M-version or migrate to a 1.0.x GA release. Mixing M-versions across starters within one BOM is unsupported.

### 9.3 Option A: Azure OpenAI GPT-4o-mini (recommended for cost)

- **Dependency change:** Add `spring-ai-starter-model-azure-openai` to `build.gradle` alongside the existing Bedrock starter. Apply Strategy A from §9.1.
- **Config:** `application-azure-ai.yml` as shown in §5.2.
- **Cost:** ~$0.15/1M input tokens, $0.60/1M output tokens. For a demo workload: ~$0.05–$0.50/month.
- **Authentication — pick exactly one:**
  - **API key** — set `AZURE_OPENAI_API_KEY` as a Container App secret and reference it from `application-azure-ai.yml` via `api-key: ${AZURE_OPENAI_API_KEY}`.
  - **Managed Identity (recommended)** — leave `api-key` unset, add `com.azure:azure-identity` as a runtime dependency in `insight-service/build.gradle`, and grant the ACA system-assigned identity the `Cognitive Services OpenAI User` role on the Azure OpenAI resource (declared in the Terraform azure root, §6.1). Spring AI's Azure OpenAI auto-config picks up `DefaultAzureCredential` automatically when `api-key` is blank and `azure-identity` is on the classpath.

The previous "no API keys in code" claim was misleading: the Azure OpenAI starter accepts either mode, but the two are mutually exclusive and require different `build.gradle` and `application-azure-ai.yml` shapes.

### 9.4 Option B: Azure AI Foundry + Claude (model parity with AWS)

- **Dependency change:** Same as Option A — Spring AI's Azure OpenAI starter works with AI Foundry endpoints that expose the OpenAI-compatible chat completions API.
- **Config:** Point `AZURE_OPENAI_ENDPOINT` to the AI Foundry project endpoint and set `AZURE_OPENAI_DEPLOYMENT` to the deployed Claude model name.
- **Cost:** Verify against the Foundry catalogue at deployment time. The specific Claude Haiku 4.5 SKU may not be available; pricing parity with AWS Bedrock is not guaranteed.
- **Trade-off:** Slightly more complex setup (AI Foundry project + model deployment required).

### 9.5 What stays the same

- `MockAiInsightService` and `MockInsightAdvisor` — **profile expressions widened** per §5.3.2 (`@Profile("!bedrock & !azure-ai")`), but the implementations themselves are unchanged. Local dev (`SPRING_PROFILES_ACTIVE=local`) still selects them.
- The `InsightService` business logic — no changes needed; Spring AI's `ChatClient` API abstracts the provider.
- The `application-bedrock.yml` — stays for the AWS path. Add `spring.ai.model.chat: bedrock-converse` to it (Strategy A).

---

## 10. Frontend Hosting on Azure

The current frontend is a Next.js static export deployed to S3 + CloudFront. On Azure, two options:

| Option | Service | Cost | Notes |
|---|---|---|---|
| **Azure Static Web Apps** | Static Web Apps Free tier | **$0/month** | 100 GB bandwidth/month free. Supports custom domains + free TLS. Best option. |
| Azure Blob Storage + Front Door | Storage + Front Door | ~$5–$10/month | Front Door Standard is $35/month — too expensive. Not recommended. |
| Azure CDN + Blob Storage | Storage + CDN | ~$1–$3/month | Viable but Static Web Apps is simpler and free |

**Recommendation: Azure Static Web Apps (Free tier).** It supports Next.js static exports, custom domains, and free TLS. The GitHub Actions deployment is a single `azure/static-web-apps-deploy@v1` action step.

---

## 11. Cost Analysis

### 11.1 Azure Cost Breakdown (Low-Traffic / Portfolio Demo)

All pricing sourced from official Azure pricing pages (May 2026).

#### Scenario A — Strict scale-to-zero (`minReplicas: 0` on all four services)

| Service | Tier | Monthly Cost | Notes |
|---|---|---|---|
| **Azure Container Registry** | Basic | ~$5.00 | $0.167/day × 30.4 days. 10 GiB storage included. |
| **Azure Container Apps** | Consumption | **$0.00** | Free grant per subscription: 180,000 vCPU-s + 360,000 GiB-s + 2M requests/month. Idle replicas = 0 stays well within this. |
| **Azure Static Web Apps** | Free | **$0.00** | 100 GB/month bandwidth, custom domain, free TLS |
| **Azure OpenAI (GPT-4o-mini)** | Pay-per-token | ~$0.05–$0.50 | Negligible for demo usage |
| **Azure Key Vault** | Standard | ~$0.03/10k ops | Optional; GitHub Secrets → ACA env vars is free |
| **Egress** | — | ~$0.00–$0.50 | Minimal for demo traffic; cross-cloud calls to Aiven/Neon/Atlas/Upstash add some egress at provider side, see §13 G4 |
| **Total** | | **~$5.00–$6.00/month** | Dominated by ACR Basic |

#### Scenario B — `minReplicas: 1` on api-gateway only (warm user-facing path)

If you keep one `api-gateway` replica running 24×7 to mask cold starts, the idle vCPU-s/GiB-s consumed in that month exceed the ACA free grant on their own:

- 1 replica × 0.5 vCPU × 730 h × 3600 s/h = **1,314,000 vCPU-s** vs. 180,000 free → ~1.13M vCPU-s billable.
- 1 replica × 1.0 GiB × 730 h × 3600 s/h = **2,628,000 GiB-s** vs. 360,000 free → ~2.27M GiB-s billable.
- Idle billing rates (Consumption, May 2026): ~$0.000004/vCPU-s and ~$0.0000025/GiB-s.
- Estimated incremental cost: **~$1.50–$3.00/month** on top of Scenario A, depending on whether the replica drops to idle or stays at active rate during request bursts.

| Component | Monthly Cost |
|---|---|
| All Scenario A items | ~$5.00–$6.00 |
| api-gateway warm replica (idle billing, after free grant) | ~$1.50–$3.00 |
| **Scenario B total** | **~$7.00–$9.00/month** |

**ACR cost note:** ACR Basic at $0.167/day ≈ $5/month is the single largest cost and is unavoidable for a private registry. **ACR Basic is *not* on Azure's "12 months free" service list** (the list covers specific compute, storage, and database SKUs only — see [Azure free services](https://azure.microsoft.com/en-us/pricing/free-services/)). New accounts can pay for ACR from the $200 / 30-day startup credit, but there is no 12-month ACR-Basic-free benefit.

**Comparison with current AWS cost:** ~$2–$3/month (Lambda free tier + Route 53). Azure Scenario A is ~$3 higher; Scenario B is ~$5 higher. Both are within the $0–$10 budget constraint.

### 11.2 Cost Optimisation Options

- **Use GitHub Container Registry (ghcr.io) instead of ACR:** Free for public repos, $0/month. Trade-off: images are public unless on a paid GitHub plan. For a portfolio project, this is acceptable and reduces the floor to ~$0–$1/month (Scenario A) or ~$2–$4/month (Scenario B).
- **Scale-to-zero everywhere:** Default on ACA Consumption. Stays inside the free grant for personal-portfolio traffic.
- **Burst the free grant to its limits:** Free-grant headroom (180k vCPU-s/month) absorbs ~100 hours of one 0.5-vCPU replica running. If `minReplicas: 1` is enabled only for api-gateway *during business hours* (via a scheduled scaling rule), the idle hours fit inside the grant and Scenario A's cost holds.
- **Azure $200 / 30-day startup credit** covers either scenario for the first month for new accounts. No 12-month ACR-Basic-free entitlement exists; do not budget for one.

---

## 12. What Is NOT Changing

To be explicit about scope:

- **No AWS Lambda code removed.** The Lambda Web Adapter, `application-aws.yml`, Terraform AWS modules, and `deploy.yml` GitHub Actions workflow are all preserved.
- **No existing Dockerfiles modified.** New `.azure` variants are added alongside.
- **No domain/DNS changes.** `vibhanshu-ai-portfolio.dev` stays on Route 53/CloudFront for the AWS path.
- **No database migrations.** Neon PostgreSQL, MongoDB Atlas, Aiven Kafka, and Upstash Redis are shared between both clouds.
- **No Spring Boot version changes.** Java 25 / Spring Boot 4.0.5 runs identically on ACA.

---

## 13. Risk Assessment

### 13.1 Top Risks (Code Blockers)

The following four risks are runtime blockers — without the corresponding mitigation, the Azure deployment fails to start or silently misbehaves.

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| G1 | `JwtDecoderConfig` is `@Profile({"local", "aws"})` → no `ReactiveJwtDecoder` bean under `prod,azure` → gateway 401/500 on every authenticated request | **High (blocker)** | Add `"azure"` to the profile array (§5.3.1). One-line change. |
| G2 | `MockAiInsightService` / `MockInsightAdvisor` are `@Profile("!bedrock")` → silently activate on Azure alongside the new Azure adapter, producing mock responses or `NoUniqueBeanDefinitionException` | **High (blocker)** | Widen exclusion to `@Profile("!bedrock & !azure-ai")` and add `@Profile("azure-ai")` to the new Azure adapter classes (§5.3.2). |
| G3 | Bedrock and Azure OpenAI starters on the same classpath both register `ChatModel` beans → autowire ambiguity at startup. Spring profiles do **not** disambiguate auto-config | **High (blocker)** | Apply `spring.ai.model.chat=bedrock-converse` / `azure-openai` per profile (Strategy A in §9.1). Profile guards from §5.3.2 select which adapter consumes the primary `ChatModel`. |
| G6 | ACA system-assigned identity needs `AcrPull` on ACR; without it, revision activation fails with `UNAUTHORIZED: authentication required` | **High (operational)** | Declare role assignment in Terraform azure root (§6.1) and a `registry { identity = "system" }` block on each Container App. |

### 13.2 Medium Risks

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| G4 | Cross-cloud network latency: Aiven Kafka, Neon, MongoDB Atlas, Upstash Redis are AWS-hosted (the existing deployment runs in `ap-south-1`). Running ACA in a non-collocated Azure region adds 50–200 ms RTT per dependency call and degrades Kafka throughput; egress is also billed at both ends | Medium | Pin the Azure region to `centralindia` (collocated with the existing AWS region). Verify each provider's hosting region before finalising. |
| G5 | Aiven Kafka SASL_SSL bootstrap servers are region-pinned; running ACA in a far region degrades tail latency for portfolio-event publishing | Medium | Same as G4 — region collocation. The `TruststoreExtractor` in `common-dto` continues to handle the read-only filesystem unchanged. |
| G11 | Managed Identity vs. API key for Azure OpenAI: §5.2 and §9 must agree on a single auth mode. The two are mutually exclusive and require different `build.gradle` and YAML shapes | Medium | Pick one mode in §9.3 (Managed Identity recommended). For Managed Identity, leave `api-key` unset, add `com.azure:azure-identity` runtime dependency, and grant the `Cognitive Services OpenAI User` role. |
| G13 | `minReplicas: 1` on api-gateway exceeds the ACA Consumption free grant (1.31M vCPU-s vs 180k free), adding ~$1.50–$3/month. Total Scenario B cost is ~$7–$9/month, not $5–$6 | Medium | Documented in §11.1 Scenario B. Either accept the cost or restrict warm replica to business hours via a scheduled scaler. |
| G14 | First-pull image latency on a fresh ACA node adds 5–15s on top of Spring Boot startup, pushing worst-case cold start to 20–35s | Medium | Documented in §8. Use `minReplicas: 1` on user-facing path if the warm-path UX matters more than the $1.50–$3 cost. |
| G15 | Single-root Terraform with both providers requires both clouds' credentials to plan/apply and risks cross-cloud drift | Medium | Use the separate-root layout in §6.3 (`infrastructure/terraform/aws/` and `infrastructure/terraform/azure/`) with independent state and CI jobs. |
| G18 | ACA internal DNS may briefly NXDOMAIN immediately after environment creation (~30s propagation). Terraform `apply` followed instantly by smoke tests fails | Medium | Add a `time_sleep` resource or readiness loop in `deploy-azure.yml` after the first deployment of a fresh environment. |

### 13.3 Low Risks

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| G7 | `INTERNAL_API_KEY` (used by `InternalApiKeyFilter` on portfolio/market/insight services to gate `/api/internal/**`) must be wired as a Container App secret on Azure; missing → services fail closed with 503 on internal calls | Low | Add to env-var checklist for the Azure Terraform root. |
| G8 | `CloudFrontOriginVerifyFilter` reads `System.getenv("CLOUDFRONT_ORIGIN_SECRET")` directly. The filter already no-ops when the env var is unset, so simply omitting it on Azure is sufficient. The originally proposed `@ConditionalOnProperty` annotation does not actually drive the runtime behaviour | Low | Do not set `CLOUDFRONT_ORIGIN_SECRET` on ACA (§5.3.4). No Java change required. |
| G9 | `application-prod.yml` for api-gateway has `response-timeout: 55s` justified by CloudFront's 60s origin-read timeout. The rationale comment becomes stale on Azure but the value is still safe (well below ACA's 240s ingress default and Front Door Standard's 60s) | Low | Optionally override in `application-azure.yml` with an Azure-appropriate justification; otherwise leave as-is. |
| G10 | Spring AI `2.0.0-M4` is a milestone with API churn between M-versions. Mixing M-versions across starters within one BOM is unsupported | Low–Medium | Pin both starters to the same M-version, or migrate to 1.0.x GA before adding the Azure starter. Decide before Phase 2 starts. |
| G12 | The original §11 footnote claimed ACR Basic is part of Azure's 12-month-free tier. ACR is not on that list | Low (cost projection error) | Corrected in §11.1. Removed the claim; total cost adjusted. |
| G16 | `azure/login` for `deploy-azure.yml` should use OIDC federated credentials, not a client secret | Low (security hygiene) | Documented in §6.4. Federated credential on Entra app registration scoped to `repo:<owner>/<repo>`. |
| G17 | `InfrastructureHealthLogger` is `@Profile("aws")` — silent loss of `[INFRA-OK]/[INFRA-FAIL]` startup probes on Azure | Low | Widen to `{"aws", "azure"}` (§5.3.3). |
| — | Kafka SASL_SSL truststore on ACA | Low | `TruststoreExtractor` in `common-dto` already handles read-only filesystem; same mechanism works on ACA. |
| — | Azure OpenAI quota limits | Low | GPT-4o-mini has generous default quotas; demo workload is well within limits. |
| G19 | Caller URL on Azure must be `http://portfolio-service` (port 80, ACA-mapped), **not** `http://portfolio-service:8081`. Inline ports break internal DNS resolution | Low | Documented in §7. Set `PORTFOLIO_SERVICE_URL=http://portfolio-service` (no port) and `ingress.targetPort: 8081` in Terraform. |

---

## 14. Implementation Plan (For Approval)

The following is the ordered list of changes required. **None of these are executed until explicit approval is given.**

### Phase 1: Dockerfiles (4 new files)
- Add `{service}/Dockerfile.azure` for each of the 4 services
- Remove LWA sidecar (api-gateway: also remove `AWS_LWA_PORT`/`AWS_LWA_READINESS_CHECK_PATH`); base-image swap is optional

### Phase 2: Spring Profiles + Code-Level Profile Guards (5 new files, 4 small Java edits)
- Add `application-azure.yml` for each of the 4 services
- Add `application-azure-ai.yml` for insight-service (includes `spring.ai.model.chat: azure-openai`)
- Update `application-bedrock.yml` to add `spring.ai.model.chat: bedrock-converse` (Strategy A from §9.1)
- **Java profile-guard edits (§5.3):**
  - `JwtDecoderConfig.@Profile({"local", "aws"})` → `@Profile({"local", "aws", "azure"})` (G1, blocker)
  - `MockAiInsightService.@Profile("!bedrock")` → `@Profile("!bedrock & !azure-ai")` (G2, blocker)
  - `MockInsightAdvisor.@Profile("!bedrock")` → `@Profile("!bedrock & !azure-ai")` (G2, blocker)
  - `InfrastructureHealthLogger.@Profile("aws")` → `@Profile({"aws", "azure"})` (G17, minor)
- Add new `AzureOpenAiInsightService` and `AzureOpenAiInsightAdvisor` classes annotated `@Profile("azure-ai")`
- Add `spring-ai-starter-model-azure-openai` (and `com.azure:azure-identity` if Managed Identity is chosen) to `insight-service/build.gradle`
- **No change to `CloudFrontOriginVerifyFilter`** — env-var omission on Azure is sufficient (§5.3.4)

### Phase 3: Azure Infrastructure (Terraform — separate root)
- Relocate existing root to `infrastructure/terraform/aws/` (no logic changes)
- Add `infrastructure/terraform/azure/` root with `main.tf`, `variables.tf`, `outputs.tf`, `providers.tf`, `backend-azure.hcl`
- Inside the Azure root: ACR, ACA environment, 4 Container Apps, `AcrPull` role assignments per app, `Cognitive Services OpenAI User` role assignment for insight-service (Managed Identity path), Static Web App (optional), Key Vault (optional)
- Pin region to `centralindia` (collocated with existing data-tier providers)

### Phase 4: CI/CD (2 new GitHub Actions workflows, OIDC auth)
- Add `.github/workflows/deploy-azure.yml` (image build → ACR push → ACA revision update)
- Add `.github/workflows/terraform-azure.yml` (Terraform plan/apply against the azure root)
- Configure Entra app registration with federated credential scoped to `repo:<owner>/<repo>:ref:refs/heads/main`
- Grant SP roles: `AcrPush` on ACR + `Contributor` on resource group (+ `User Access Administrator` only if Terraform creates role assignments itself)

### Phase 5: Frontend
- Add Azure Static Web Apps deployment step to `deploy-azure.yml`

---

## 15. Summary

| Dimension | Assessment |
|---|---|
| **Feasibility** | High. The codebase is cloud-agnostic at the application layer; all Azure-specific concerns are in profile YAML, profile-guard annotations, and infrastructure-as-code. |
| **Effort** | Medium. ~15 new/modified files plus 4 small Java profile-guard edits identified during validation. No domain or service-layer logic changes. |
| **Cost** | ~$5–$6/month under strict scale-to-zero (Scenario A), ~$7–$9/month with `minReplicas: 1` on api-gateway (Scenario B). Drops to ~$0–$1 using GitHub Container Registry. ACR Basic is not part of any 12-month-free entitlement. |
| **AWS preservation** | Full. Zero AWS code removed. Both paths coexist via Spring profiles and disjoint Spring AI `model.chat` selectors. |
| **Risk** | Three code-level blockers (G1, G2, G3) and one operational blocker (G6) must be addressed in Phase 2/3. All are small, well-understood changes; severity is "blocker if forgotten" rather than "structurally hard". |
| **Timeline estimate** | 2–3 days of focused implementation work (revised up from 1–2 days to account for the four code-level guards, separate Terraform root, OIDC setup, and Spring AI classpath disambiguation testing). |

---

*Sources:*
- *[Azure Container Apps Pricing](https://azure.microsoft.com/en-us/pricing/details/container-apps/) — Microsoft Azure (May 2026)*
- *[Azure Container Registry Pricing](https://azure.microsoft.com/en-us/pricing/details/container-registry/) — Microsoft Azure (May 2026)*
- *[Azure Container Apps Billing](https://learn.microsoft.com/azure/container-apps/billing) — Microsoft Learn (May 2026)*
- *[Azure Static Web Apps Pricing](https://azure.microsoft.com/en-us/pricing/details/app-service/static/) — Microsoft Azure*
- *[ACR Basic tier: $0.167/day](https://learn.microsoft.com/en-ca/azure/container-registry/container-registry-skus) — Microsoft Learn*
- *[ACA free grant: 180k vCPU-s, 360k GiB-s, 2M requests/month](https://learn.microsoft.com/azure/container-apps/billing) — Microsoft Learn*
- *Content was rephrased for compliance with licensing restrictions*
