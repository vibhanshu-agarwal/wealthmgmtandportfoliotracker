# Design Document: Lambda Service Split

## Overview

This feature migrates the wealth management platform from a single deployed Lambda (`wealth-mgmt-backend-lambda` running only api-gateway) to four independently deployed Lambda functions — one per microservice. All four functions use the Image package type, backed by their existing multi-stage Dockerfiles with Spring Boot AOT compilation.

The migration also replaces the Ollama AI adapter in insight-service with a live AWS Bedrock (Claude Haiku 4.5, US cross-region inference profile) adapter, and adds Redis caching for Bedrock responses to control API costs.

### Goals

- All four services independently deployable via ECR image push + `update-function-code`
- Consistent Image package type across all Lambdas (no Zip/S3 artifacts)
- insight-service calls real Bedrock in production; mock falls back in CI/local
- Redis caches Bedrock responses (60 min sentiment, 30 min portfolio analysis)
- Terraform owns all environment variables; deploy.yml is image-only
- Legacy `wealth-mgmt-backend-lambda` decommissioned after all four are live

### Non-Goals

- GraalVM native image compilation (AOT is sufficient for this phase)
- SnapStart (requires Java 21 managed runtime; Image Lambdas use custom JRE)
- Multi-region deployment
- Changing the Spring Cloud Gateway routing logic

---

## Architecture

### Current State

```
CloudFront → wealth-mgmt-backend-lambda (api-gateway only, Image)
                    ↓ (no downstream Lambdas deployed)
```

portfolio-service, market-data-service, and insight-service exist in Terraform as Zip Lambdas but are not deployed or reachable.

### Target State

```
CloudFront → wealth-api-gateway (Image Lambda)
                    ├── PORTFOLIO_SERVICE_URL  → wealth-portfolio-service (Image Lambda)
                    ├── MARKET_DATA_SERVICE_URL → wealth-market-data-service (Image Lambda)
                    └── INSIGHT_SERVICE_URL    → wealth-insight-service (Image Lambda)
                                                        ↓
                                               wealth-portfolio-service (for portfolio context)
                                                        ↓
                                               Amazon Bedrock (Claude Haiku 4.5, us.* inference profile)
                                                        ↑↓
                                               Redis (Upstash, cache layer)
```

### Two-Phase Terraform Apply

Function URLs for portfolio/market-data/insight are not known until after the first `terraform apply` creates those Lambda resources. The api-gateway Lambda environment references these URLs, creating a dependency cycle on first apply.

**Resolution:** The compute module uses a conditional expression:

```hcl
PORTFOLIO_SERVICE_URL = var.portfolio_function_url != ""
  ? var.portfolio_function_url
  : aws_lambda_function_url.portfolio.function_url
```

- **Phase 1 apply:** All four Lambdas and Function URLs are created. api-gateway gets the computed URLs directly from `aws_lambda_function_url.*` outputs (Terraform resolves these within the same plan).
- **Phase 2 apply (if needed):** If cross-account or external URL overrides are required, set `TF_VAR_portfolio_function_url` etc. and re-apply.

In practice, a single apply resolves all references within the same state graph. The `TF_VAR_*_function_url` overrides exist for edge cases only.

### Deploy Pipeline Flow

```
push to main
    │
    ├── deploy-frontend (S3 sync + CloudFront invalidation)
    │
    └── deploy-backend
            ├── ECR login
            ├── Build + push api-gateway image
            ├── Wait for Lambda Successful → update-function-code (wealth-api-gateway)
            ├── Build + push portfolio-service image
            ├── Wait for Lambda Successful → update-function-code (wealth-portfolio-service)
            ├── Build + push market-data-service image
            ├── Wait for Lambda Successful → update-function-code (wealth-market-data-service)
            ├── Build + push insight-service image
            └── Wait for Lambda Successful → update-function-code (wealth-insight-service)
```

Builds are sequential to keep the workflow simple. Each `update-function-code` call polls `LastUpdateStatus` before proceeding.

---

## Components and Interfaces

### 1. Terraform Compute Module (`infrastructure/terraform/modules/compute/`)

**`main.tf` changes:**

- Convert `aws_lambda_function.portfolio`, `.market_data`, `.insight` from Zip to Image:
  - Remove: `runtime`, `handler`, `s3_bucket`, `s3_key`, `layers`
  - Add: `package_type = "Image"`, `image_uri = var.{service}_image_uri`
  - Add: `lifecycle { ignore_changes = [image_uri] }`
- Add `SPRING_PROFILES_ACTIVE = "prod,aws,bedrock"` to insight-service environment
- Add `PORTFOLIO_SERVICE_URL` to insight-service environment (for portfolio context calls)
- Add inline comment on `SPRING_PROFILES_ACTIVE` explaining the `local` base profile behavior

**`variables.tf` changes:**

- Add: `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri` (type `string`)
- Remove: `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, `lambda_adapter_layer_arn`

### 2. ECR Repositories (`infrastructure/terraform/ecr.tf`)

New file at the Terraform root (not inside a module) defining three ECR repositories:

```hcl
resource "aws_ecr_repository" "portfolio" {
  name                 = "wealth-portfolio-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}
# (same pattern for market_data, insight)
```

Outputs expose the repository URLs for reference in CI/CD documentation.

### 3. Root Terraform (`infrastructure/terraform/variables.tf` + `main.tf`)

**`variables.tf` changes:**

- Add: `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri`
- Remove: `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, `lambda_adapter_layer_arn`

**`main.tf` changes:**

- Pass new image URI vars to compute module
- Remove S3 key and adapter layer ARN pass-throughs

### 4. Terraform Pipeline (`.github/workflows/terraform.yml`)

- Add `TF_VAR_portfolio_image_uri`, `TF_VAR_market_data_image_uri`, `TF_VAR_insight_image_uri` env vars constructed from `${{ secrets.AWS_ACCOUNT_ID }}.dkr.ecr.${{ secrets.AWS_REGION }}.amazonaws.com/${{ secrets.ECR_REPOSITORY_NAME_<SERVICE> }}:latest`
- Remove `-var="s3_key_portfolio=..."`, `-var="s3_key_market_data=..."`, `-var="s3_key_insight=..."`, `-var="lambda_adapter_layer_arn=..."`
- Remove `TF_VAR_*` entries for `S3_KEY_PORTFOLIO`, `S3_KEY_MARKET_DATA`, `S3_KEY_INSIGHT`, `LAMBDA_ADAPTER_LAYER_ARN`

### 5. Deploy Pipeline (`.github/workflows/deploy.yml`)

The existing `deploy-backend` job is extended to build and deploy all four services sequentially. Each service follows the same pattern:

```yaml
- name: Build and push {service} image
  env:
    ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
    ECR_REPOSITORY: ${{ secrets.ECR_REPOSITORY_NAME_{SERVICE} }}
    IMAGE_TAG_SHA: ${{ github.sha }}
  run: |
    docker buildx build \
      --platform linux/amd64 \
      --provenance=false \
      --sbom=false \
      -f {service}/Dockerfile \
      -t "${ECR_REGISTRY}/${ECR_REPOSITORY}:latest" \
      -t "${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG_SHA}" \
      --push .

- name: Update {service} Lambda
  env:
    LAMBDA_FUNCTION_NAME: ${{ secrets.LAMBDA_FUNCTION_NAME_{SERVICE} }}
  run: |
    # poll LastUpdateStatus then update-function-code
```

The legacy `LAMBDA_FUNCTION_NAME` secret reference is replaced by `LAMBDA_FUNCTION_NAME_API_GATEWAY` for the api-gateway step.

**New secrets required:**

| Secret                             | Value                        |
| ---------------------------------- | ---------------------------- |
| `ECR_REPOSITORY_NAME_PORTFOLIO`    | `wealth-portfolio-service`   |
| `ECR_REPOSITORY_NAME_MARKET_DATA`  | `wealth-market-data-service` |
| `ECR_REPOSITORY_NAME_INSIGHT`      | `wealth-insight-service`     |
| `LAMBDA_FUNCTION_NAME_API_GATEWAY` | `wealth-api-gateway`         |
| `LAMBDA_FUNCTION_NAME_PORTFOLIO`   | `wealth-portfolio-service`   |
| `LAMBDA_FUNCTION_NAME_MARKET_DATA` | `wealth-market-data-service` |
| `LAMBDA_FUNCTION_NAME_INSIGHT`     | `wealth-insight-service`     |

### 6. portfolio-service Dockerfile

Add `-Dspring.aot.enabled=true` to the `ENTRYPOINT` in the runtime stage:

```dockerfile
# Before
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# After
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]
```

This matches the pattern already used by `api-gateway/Dockerfile`, `market-data-service/Dockerfile`, and `insight-service/Dockerfile`.

### 7. insight-service: Bedrock AI Adapters

**`build.gradle` change:**

```groovy
// Remove (Ollama is a local inference server, incompatible with Lambda):
implementation 'org.springframework.ai:spring-ai-starter-model-ollama'

// Add (Bedrock Converse API — same ChatClient abstraction, AWS-native invocation):
implementation 'org.springframework.ai:spring-ai-starter-model-bedrock-converse'
```

**New class: `BedrockAiInsightService`** (`@Profile("bedrock")`)

Replaces `MockBedrockAiInsightService`. Uses `ChatClient` backed by `BedrockProxyChatModel` (auto-configured by the starter, targeting the `us.anthropic.claude-haiku-4-5-20251001-v1:0` inference profile). Annotated `@Cacheable(value = "sentiment", key = "#ticker")`.

**New class: `BedrockInsightAdvisor`** (`@Profile("bedrock")`)

Replaces the mock advisor under the `bedrock` profile. Same `AnalysisResult` entity mapping contract. Annotated `@Cacheable(value = "portfolio-analysis", key = "#portfolio.id()")`.

**Ollama adapters have been deleted.** `OllamaAiInsightService`, `OllamaInsightAdvisor`, their property test, and `application-ollama.yml` were removed; the Ollama starter is no longer on the classpath. Local dev uses `MockAiInsightService` / `MockInsightAdvisor` exclusively — both guarded by `@Profile("!bedrock")`.

**Profile activation on Lambda:** `SPRING_PROFILES_ACTIVE = "prod,aws,bedrock"` set in Terraform compute module for `wealth-insight-service`. Local docker-compose defaults to `SPRING_PROFILES_ACTIVE=local` (mocks); `local,bedrock` is an opt-in override for smoke-testing Bedrock from a developer machine.

### 8. insight-service: Redis Cache Configuration

**New class: `CacheConfig`** (`@Configuration`, `@EnableCaching`)

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        return RedisCacheManager.builder(factory)
            .withCacheConfiguration("sentiment",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(60)))
            .withCacheConfiguration("portfolio-analysis",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(30)))
            .build();
    }
}
```

Cache-miss fallthrough on Redis unavailability is handled by wrapping the `RedisCacheManager` with `CacheErrorHandler` that logs at WARN and returns null (treated as a miss by Spring's caching abstraction).

**`application.yml` change:** Add `spring.cache.type: simple` for local/CI (no Redis required).

**`application-prod.yml` change:** Add `spring.cache.type: redis` and TTL config.

### 9. insight-service: Redis URL Fix

**`application.yml` change:**

```yaml
# Remove:
spring:
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}

# Add:
spring:
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
  cache:
    type: simple  # local/CI: in-memory; overridden to redis in application-prod.yml
```

`application-prod.yml` already uses `REDIS_URL` correctly and remains unchanged for the Redis connection property.

### 10. insight-service: application-aws.yml

Add Bedrock configuration:

```yaml
spring:
  ai:
    bedrock:
      aws:
        region: us-east-1
      converse:
        chat:
          options:
            model: us.anthropic.claude-haiku-4-5-20251001-v1:0
```

No API key needed — the Lambda execution role's IAM credentials are picked up automatically by the AWS SDK default credential chain.

---

## Data Models

### Lambda Environment Variables (per function)

**`wealth-api-gateway`**

| Variable                   | Source                     | Notes         |
| -------------------------- | -------------------------- | ------------- |
| `SPRING_PROFILES_ACTIVE`   | Terraform                  | `prod,aws`    |
| `REDIS_URL`                | `local.runtime_secrets`    | Rate limiting |
| `KAFKA_BOOTSTRAP_SERVERS`  | `local.runtime_secrets`    |               |
| `KAFKA_SASL_USERNAME`      | `local.runtime_secrets`    |               |
| `KAFKA_SASL_PASSWORD`      | `local.runtime_secrets`    |               |
| `PORTFOLIO_SERVICE_URL`    | Computed from Function URL |               |
| `MARKET_DATA_SERVICE_URL`  | Computed from Function URL |               |
| `INSIGHT_SERVICE_URL`      | Computed from Function URL |               |
| `AUTH_JWK_URI`             | Terraform var              |               |
| `CLOUDFRONT_ORIGIN_SECRET` | Terraform var              |               |

**`wealth-portfolio-service`**

| Variable                  | Source                  | Notes           |
| ------------------------- | ----------------------- | --------------- |
| `SPRING_PROFILES_ACTIVE`  | Terraform               | `prod,aws`      |
| `SPRING_DATASOURCE_URL`   | Terraform var           | Neon PostgreSQL |
| `REDIS_URL`               | `local.runtime_secrets` |                 |
| `KAFKA_BOOTSTRAP_SERVERS` | `local.runtime_secrets` |                 |
| `KAFKA_SASL_USERNAME`     | `local.runtime_secrets` |                 |
| `KAFKA_SASL_PASSWORD`     | `local.runtime_secrets` |                 |

**`wealth-market-data-service`**

| Variable                  | Source                  | Notes         |
| ------------------------- | ----------------------- | ------------- |
| `SPRING_PROFILES_ACTIVE`  | Terraform               | `prod,aws`    |
| `SPRING_DATA_MONGODB_URI` | Terraform var           | MongoDB Atlas |
| `KAFKA_BOOTSTRAP_SERVERS` | `local.runtime_secrets` |               |
| `KAFKA_SASL_USERNAME`     | `local.runtime_secrets` |               |
| `KAFKA_SASL_PASSWORD`     | `local.runtime_secrets` |               |

**`wealth-insight-service`**

| Variable                  | Source                     | Notes              |
| ------------------------- | -------------------------- | ------------------ |
| `SPRING_PROFILES_ACTIVE`  | Terraform                  | `prod,aws,bedrock` |
| `REDIS_URL`               | `local.runtime_secrets`    | Cache + connection |
| `KAFKA_BOOTSTRAP_SERVERS` | `local.runtime_secrets`    |                    |
| `KAFKA_SASL_USERNAME`     | `local.runtime_secrets`    |                    |
| `KAFKA_SASL_PASSWORD`     | `local.runtime_secrets`    |                    |
| `PORTFOLIO_SERVICE_URL`   | Computed from Function URL | Service-to-service |

### Cache Key Design

| Cache Name           | Key                             | TTL    | Backing                      |
| -------------------- | ------------------------------- | ------ | ---------------------------- |
| `sentiment`          | ticker symbol (e.g. `"AAPL"`)   | 60 min | Redis (prod), simple (local) |
| `portfolio-analysis` | portfolio ID (`portfolio.id()`) | 30 min | Redis (prod), simple (local) |

### ECR Repository Names

| Service             | Repository Name                               |
| ------------------- | --------------------------------------------- |
| api-gateway         | (existing, from `ECR_REPOSITORY_NAME` secret) |
| portfolio-service   | `wealth-portfolio-service`                    |
| market-data-service | `wealth-market-data-service`                  |
| insight-service     | `wealth-insight-service`                      |

---

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

This feature is primarily infrastructure migration (Terraform, Dockerfiles, CI/CD YAML) and Spring profile wiring. The majority of acceptance criteria are infrastructure configuration checks (SMOKE/INTEGRATION) that do not benefit from property-based testing. However, the insight-service Bedrock and Redis caching logic contains pure Java code with testable universal properties.

**Prework summary:** Of the 40+ acceptance criteria across 12 requirements, the vast majority are SMOKE (Terraform plan assertions, YAML content checks, CI/CD YAML configuration) or INTEGRATION (full apply, runtime behavior). Four criteria in Requirements 11–12 are PROPERTY-testable: cache hit behavior for sentiment (12.2), cache hit behavior for portfolio analysis (12.3), Redis unavailability fallthrough (12.4 — unified to cover both methods), and the InsightAdvisor empty-portfolio contract (11.3). After property reflection, Properties 1 and 2 (Spring profile bean activation) are EXAMPLE tests — Spring context loading is deterministic, not input-varying — and are moved to the Testing Strategy. The Redis unavailability property is unified into a single property covering both `getSentiment` and `analyze` since the behavior and test strategy are identical.

### Property 1: Sentiment cache hit avoids Bedrock call

_For any_ non-blank ticker string, if `BedrockAiInsightService.getSentiment(ticker)` has been called once and the result is stored in the `sentiment` cache, then a second call with the same ticker SHALL return the cached value without invoking the underlying `ChatClient`.

**Validates: Requirements 12.2**

### Property 2: Portfolio analysis cache hit avoids Bedrock call

_For any_ portfolio with a non-null ID, if `BedrockInsightAdvisor.analyze(portfolio)` has been called once and the result is stored in the `portfolio-analysis` cache, then a second call with the same portfolio ID SHALL return the cached `AnalysisResult` without invoking the underlying `ChatClient`.

**Validates: Requirements 12.3**

### Property 3: Redis unavailability falls through gracefully for any input

_For any_ non-blank ticker string or non-null portfolio, if the Redis cache is unavailable (`RedisConnectionFailureException`), then both `BedrockAiInsightService.getSentiment(ticker)` and `BedrockInsightAdvisor.analyze(portfolio)` SHALL complete without throwing an exception — the cache error SHALL be logged at WARN level and the call SHALL fall through to the underlying `ChatClient`.

**Validates: Requirements 12.4**

### Property 4: Empty portfolio returns zero risk score for any InsightAdvisor implementation

_For any_ `InsightAdvisor` implementation (mock, Ollama, or Bedrock), calling `analyze` with a `PortfolioDto` that has zero holdings SHALL return an `AnalysisResult` with `riskScore == 0` and empty `concentrationWarnings` and `rebalancingSuggestions` lists, without making any AI API call.

**Validates: Requirements 11.3** (BedrockInsightAdvisor must honor the InsightAdvisor contract defined in the domain port)

---

## Error Handling

### Lambda Cold Start Failures

**Symptom:** `INIT_REPORT Status: error` or `Max Memory Used: 4 MB` (JVM crash before Spring context loads).

**Causes and mitigations:**

| Cause                                                  | Mitigation                                                           |
| ------------------------------------------------------ | -------------------------------------------------------------------- |
| Missing `-Dspring.aot.enabled=true`                    | Fixed in portfolio-service Dockerfile (Requirement 2)                |
| Wrong `package_type` (Zip vs Image)                    | Terraform conversion (Requirement 1)                                 |
| Missing required env var at startup                    | Terraform injects all vars; deploy.yml never touches env             |
| Bedrock dependency autoconfigures without model config | `application-aws.yml` sets model ID; only active under `aws` profile |

### Bedrock API Errors

`BedrockAiInsightService` and `BedrockInsightAdvisor` follow the same error handling pattern as `OllamaAiInsightService`:

- Empty/null response → throw `AdvisorUnavailableException`
- Any other exception → log at WARN, wrap in `AdvisorUnavailableException`
- Callers (controllers) handle `AdvisorUnavailableException` and return an appropriate HTTP error response

### Redis Cache Errors

Spring's `CacheErrorHandler` interface is implemented to intercept cache operation failures:

```java
@Bean
public CacheErrorHandler cacheErrorHandler() {
    return new SimpleCacheErrorHandler() {
        @Override
        public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
            log.warn("Cache GET failed for cache={} key={}: {}", cache.getName(), key, e.getMessage());
            // returning normally causes Spring to treat this as a cache miss
        }
        // handleCachePutError, handleCacheEvictError similarly
    };
}
```

This ensures Redis unavailability is non-fatal: the request falls through to Bedrock, and the result is not cached (next request will also fall through).

### Terraform Two-Phase Apply

If `aws_lambda_function_url.*` references cannot be resolved in a single plan (e.g. resources are being created for the first time and Terraform cannot determine the URL value at plan time), the plan will show `(known after apply)` for the api-gateway environment variables. This is expected and resolves on apply. If a second apply is needed to stabilize, the `TF_VAR_*_function_url` overrides can be set manually.

### deploy.yml Lambda Update Failures

Each `update-function-code` step polls `LastUpdateStatus` for up to 30 attempts (5s sleep each = 150s max). If status is `Failed`, the step exits non-zero and halts the pipeline. This prevents a broken image from being silently deployed.

---

## Testing Strategy

### Unit Tests

Unit tests cover the pure Java logic in insight-service. They do not require a running Lambda, Redis, or Bedrock connection.

**`BedrockAiInsightServiceTest`**

- Verify `getSentiment` returns non-blank string for a valid ticker (mock `ChatClient`)
- Verify `AdvisorUnavailableException` is thrown when `ChatClient` returns null
- Verify `AdvisorUnavailableException` is thrown when `ChatClient` throws

**`BedrockInsightAdvisorTest`**

- Verify `analyze` returns `AnalysisResult(0, [], [])` for empty portfolio (no ChatClient call)
- Verify `analyze` returns clamped risk score when model returns out-of-range value (mock `ChatClient`)
- Verify `AdvisorUnavailableException` is thrown when `ChatClient` returns null

**`CacheConfigTest`**

- Verify `RedisCacheManager` is configured with `sentiment` (60 min TTL) and `portfolio-analysis` (30 min TTL) cache names

**Spring context slice tests (`@SpringBootTest` with `webEnvironment = NONE`)**

- Verify `bedrock` profile activates `BedrockAiInsightService` and `BedrockInsightAdvisor` (EXAMPLE — deterministic, not input-varying)
- Verify no-profile activates `MockAiInsightService` and not `BedrockAiInsightService` (EXAMPLE)

### Property-Based Tests

Property-based tests use [jqwik](https://jqwik.net/) (the standard PBT library for Java/JUnit 5). Each test runs a minimum of 100 iterations.

**Tag format:** `// Feature: lambda-service-split, Property {N}: {property_text}`

**Property 1 test** — `SentimentCacheHitTest`

- Generator: arbitrary non-blank ticker strings (alphanumeric, 1–10 chars)
- Mock `ChatClient` returns a fixed string on first call, throws `RuntimeException` on second call
- Property: second `getSentiment(ticker)` call returns the cached value without invoking `ChatClient`
- Tag: `// Feature: lambda-service-split, Property 1: sentiment cache hit avoids Bedrock call`

**Property 2 test** — `PortfolioAnalysisCacheHitTest`

- Generator: arbitrary portfolio IDs (UUID strings), `PortfolioDto` with non-empty holdings
- Mock `ChatClient` returns a fixed `AnalysisResult` on first call, throws on second
- Property: second `analyze(portfolio)` call returns the cached result without invoking `ChatClient`
- Tag: `// Feature: lambda-service-split, Property 2: portfolio analysis cache hit avoids Bedrock call`

**Property 3 test** — `RedisFallThroughTest`

- Generator: arbitrary non-blank ticker strings and non-null portfolio IDs
- Redis `ConnectionFactory` configured to throw `RedisConnectionFailureException` on every operation
- Property: both `getSentiment(ticker)` and `analyze(portfolio)` complete without exception; result is non-null
- Tag: `// Feature: lambda-service-split, Property 3: Redis unavailability falls through gracefully`

**Property 4 test** — `EmptyPortfolioZeroRiskTest`

- Generator: `@Provide` generates `PortfolioDto` instances with empty holdings list (varying portfolio IDs)
- Property: `BedrockInsightAdvisor.analyze(portfolio).riskScore() == 0` and both list fields are empty
- No `ChatClient` mock needed — empty portfolio short-circuits before any AI call
- Tag: `// Feature: lambda-service-split, Property 4: empty portfolio returns zero risk score`

### Integration Tests

Integration tests are annotated `@Tag("integration")` and run via `./gradlew integrationTest`. They use Testcontainers (LocalStack for AWS, Redis container for cache).

**`BedrockIntegrationTest`** (LocalStack)

- Verify `BedrockAiInsightService` makes an `InvokeModel` call to the configured model ID
- Uses LocalStack's Bedrock mock endpoint

**`RedisCacheIntegrationTest`** (Testcontainers Redis)

- Verify sentiment cache entry is written to Redis after first call
- Verify cache entry expires after TTL (use short TTL override in test)
- Verify cache miss on Redis restart falls through to Bedrock without exception

### Infrastructure Tests

Terraform plan assertions in `infrastructure/terraform/scripts/assert_plan.py` (existing pattern):

- All four `aws_lambda_function` resources have `package_type = "Image"`
- `wealth-insight-service` environment contains `SPRING_PROFILES_ACTIVE` with value `prod,aws,bedrock`
- `wealth-api-gateway` environment contains `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL`
- No `aws_lambda_function` resource has `runtime`, `handler`, `s3_bucket`, or `s3_key` attributes (for portfolio/market-data/insight)
- Three `aws_ecr_repository` resources exist with correct names

### Smoke Tests (Manual, Post-Deploy)

After `terraform apply` + `deploy.yml` completes:

1. Invoke each Lambda directly via its Function URL with a health check request (`GET /actuator/health`) — expect `{"status":"UP"}`
2. Check CloudWatch logs for `INIT_REPORT Status: success` and `Max Memory Used > 100 MB` on each function
3. Invoke `wealth-insight-service` sentiment endpoint — verify response is not the mock string and contains Bedrock-generated content
4. Invoke the same endpoint twice — verify second response is identical (cache hit) and CloudWatch shows no second Bedrock invocation
