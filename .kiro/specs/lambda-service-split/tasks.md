# Implementation Plan: Lambda Service Split

## Overview

Migrate the platform from a single deployed Lambda (api-gateway only) to four independently
deployed Image Lambdas, replace the Ollama/mock Bedrock AI adapter with a live AWS Bedrock
(Claude Haiku 4.5, invoked via the US cross-region inference profile) adapter, add Redis
caching for Bedrock responses, and extend the CI/CD pipeline to build and deploy all four
services.

**Critical sequencing:** Terraform infrastructure (Phase A) must be applied before CI/CD
pipeline changes (Phase C) are merged. Application code (Phase B) can be developed in
parallel with Phase A but must be complete before Phase C. Property-based tests (Phase D)
follow Phase B. Verification (Phase E) closes the loop.

## Tasks

---

### Phase A — Terraform Infrastructure (must be applied before CI/CD changes)

- [x] A1. Create `infrastructure/terraform/ecr.tf` with three ECR repositories
  - Add `aws_ecr_repository` resources for `wealth-portfolio-service`,
    `wealth-market-data-service`, and `wealth-insight-service`
  - Set `image_tag_mutability = "MUTABLE"` and `force_delete = true` on each,
    consistent with the existing api-gateway ECR repository pattern
  - Add `output` blocks exposing the three repository URLs for CI/CD reference
  - _Requirements: 3.1, 3.2, 3.3_

- [x] A2. Add image URI variables and remove obsolete Zip variables from `infrastructure/terraform/modules/compute/variables.tf`
  - Add `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri` variables
    of type `string` with descriptive comments
  - Remove `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, and
    `lambda_adapter_layer_arn` variable declarations
  - _Requirements: 1.3, 1.6, 8.1_

- [x] A3. Convert portfolio, market-data, and insight Lambdas from Zip to Image in `infrastructure/terraform/modules/compute/main.tf`
  - [x] A3.1 Convert `aws_lambda_function.portfolio` to Image package type
    - Remove `runtime`, `handler`, `s3_bucket`, `s3_key`, `layers` attributes
    - Add `package_type = "Image"` and `image_uri = var.portfolio_image_uri`
    - Add `lifecycle { ignore_changes = [image_uri] }` block
    - _Requirements: 1.1, 1.2, 1.4, 1.5_
  - [x] A3.2 Convert `aws_lambda_function.market_data` to Image package type
    - Same pattern as A3.1 using `var.market_data_image_uri`
    - _Requirements: 1.1, 1.2, 1.4, 1.5_
  - [x] A3.3 Convert `aws_lambda_function.insight` to Image package type
    - Same pattern as A3.1 using `var.insight_image_uri`
    - Set `SPRING_PROFILES_ACTIVE = "prod,aws,bedrock"` in the environment block
      (overrides the `local.common_env` value of `"prod,aws"`)
    - Add `PORTFOLIO_SERVICE_URL` to the insight environment using the same
      conditional expression pattern as api-gateway:
      `var.portfolio_function_url != "" ? var.portfolio_function_url : aws_lambda_function_url.portfolio.function_url`
    - Add inline comment on `SPRING_PROFILES_ACTIVE` explaining that Spring Boot
      includes the base `application.yml` default profile (`local`) alongside the
      active profiles — this is expected behavior, not a misconfiguration
    - _Requirements: 1.1, 1.2, 1.4, 1.5, 5.3, 9.1, 11.4_

- [x] A4. Update root `infrastructure/terraform/variables.tf` to match compute module changes
  - Add `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri` variable
    declarations with descriptions matching the compute module
  - Remove `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, and
    `lambda_adapter_layer_arn` variable declarations
  - _Requirements: 1.3, 1.6, 8.1_

- [x] A5. Update root `infrastructure/terraform/main.tf` to wire new variables into the compute module
  - Pass `portfolio_image_uri`, `market_data_image_uri`, `insight_image_uri` to the
    `module "compute"` block
  - Remove `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, and
    `lambda_adapter_layer_arn` pass-throughs from the module call
  - _Requirements: 1.3, 1.6, 8.1_

- [x] A6. Update `infrastructure/terraform/localstack.tfvars` with stub image URIs
  - Add stub values for `portfolio_image_uri`, `market_data_image_uri`,
    `insight_image_uri` using the `000000000000.dkr.ecr.us-east-1.amazonaws.com/`
    prefix pattern already used for `api_gateway_image_uri`
  - Remove `lambda_adapter_layer_arn`, `s3_key_portfolio`, `s3_key_market_data`,
    `s3_key_insight` stub entries
  - _Requirements: 1.3, 8.1_

- [x] A7. Update `infrastructure/terraform/terraform.tfvars.example` to document new variables
  - Add commented examples for `portfolio_image_uri`, `market_data_image_uri`,
    `insight_image_uri` with the `123456789012.dkr.ecr.us-east-1.amazonaws.com/` pattern
  - Remove or comment out `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`,
    and `lambda_adapter_layer_arn` example entries
  - _Requirements: 1.3, 8.1_

- [x] A8. Checkpoint — validate Terraform changes before proceeding to CI/CD
  - Run `terraform fmt -check -recursive` in `infrastructure/terraform/`
  - Run `terraform validate` in `infrastructure/terraform/` (requires `terraform init`)
  - Confirm no references to removed variables remain in any `.tf` file
  - Ask the user if questions arise before continuing to Phase C.

---

### Phase B — Application Code (parallel with Phase A)

- [x] B1. Fix `portfolio-service/Dockerfile` AOT flag
  - In the runtime stage `ENTRYPOINT`, add `-Dspring.aot.enabled=true` as a JVM
    argument: `ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]`
  - This matches the pattern already used by `api-gateway/Dockerfile`,
    `market-data-service/Dockerfile`, and `insight-service/Dockerfile`
  - _Requirements: 2.1, 2.2_

- [x] B2. Fix Redis connection in `insight-service/src/main/resources/application.yml`
  - Replace `spring.data.redis.host` / `spring.data.redis.port` properties with
    `spring.data.redis.url: ${REDIS_URL:redis://localhost:6379}`
  - Add `spring.cache.type: simple` under `spring.cache` for local/CI (no Redis required)
  - Add inline comment on `spring.profiles.active` noting the `local` fallback is
    intentional and is overridden to `prod,aws` (or `prod,aws,bedrock`) by the Lambda
    environment variable at runtime
  - _Requirements: 6.1, 6.2, 9.2, 12.7_

- [x] B3. Update `insight-service/src/main/resources/application-prod.yml` for Redis caching
  - Add `spring.cache.type: redis` under the `spring` block
  - Add TTL configuration for the `sentiment` cache (60 min) and `portfolio-analysis`
    cache (30 min) under `spring.cache.redis.time-to-live` or via `CacheConfig` bean
    (the bean approach in B6 is authoritative; this entry documents the intent)
  - _Requirements: 12.6_

- [x] B4. Add Bedrock configuration (region in `application-aws.yml`, model in `application-bedrock.yml`)
  - In `application-aws.yml`, add `spring.ai.bedrock.aws.region: us-east-1` and a
    comment explaining no API key is needed — the Lambda execution role's IAM
    credentials are picked up automatically by the AWS SDK default credential chain.
  - In `application-bedrock.yml`, configure
    `spring.ai.bedrock.converse.chat.options.model: us.anthropic.claude-haiku-4-5-20251001-v1:0`
    plus `temperature: 0.2` under the same `converse.chat.options` block. The
    `converse` level is required — the Bedrock Converse starter ignores
    `spring.ai.bedrock.chat.options.model` (without `converse`) silently.
  - Do NOT duplicate the model key in `application-aws.yml`; keep a single source of truth
    in `application-bedrock.yml`.
  - _Requirements: 11.5_

- [x] B5. Update `insight-service/build.gradle` — swap Ollama for Bedrock Converse and add jqwik
  - [x] B5.1 Replace the Ollama starter with the Bedrock Converse starter
    - Remove `implementation 'org.springframework.ai:spring-ai-starter-model-ollama'`
    - Add `implementation 'org.springframework.ai:spring-ai-starter-model-bedrock-converse'`
    - Add a comment explaining the removal: Ollama is a local inference server
      incompatible with Lambda; Bedrock Converse API provides the same Spring AI
      `ChatClient` abstraction with AWS-native invocation
    - _Requirements: 11.1_
  - [ ]\* B5.2 Add jqwik as a test dependency for property-based tests
    - Add `testImplementation 'net.jqwik:jqwik:1.9.2'` to the `dependencies` block
    - _Requirements: 12.2, 12.3, 12.4_ (enables Phase D tests)

- [x] B6. Create `insight-service/src/main/java/com/wealth/insight/infrastructure/redis/CacheConfig.java`
  - Annotate with `@Configuration` and `@EnableCaching`
  - Define a `RedisCacheManager` bean with:
    - `sentiment` cache: 60-minute TTL
    - `portfolio-analysis` cache: 30-minute TTL
  - Implement `CachingConfigurer` to provide a `CacheErrorHandler` that logs at WARN
    and returns normally on `handleCacheGetError`, `handleCachePutError`, and
    `handleCacheEvictError` — Redis unavailability must be non-fatal (treated as a miss)
  - _Requirements: 12.1, 12.4_

- [x] B7. Create `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/BedrockAiInsightService.java`
  - Annotate with `@Service` and `@Profile("bedrock")`
  - Implement `AiInsightService` with the same system prompt and `buildPrompt` logic
    as `OllamaAiInsightService`
  - Inject `ChatClient.Builder` and `MarketDataService` via constructor
  - Annotate `getSentiment(String ticker)` with
    `@Cacheable(value = "sentiment", key = "#ticker")`
  - Follow the same error-handling pattern: empty/null response throws
    `AdvisorUnavailableException`; other exceptions are logged at WARN and wrapped
  - This class replaces `MockBedrockAiInsightService` under the `bedrock` profile
  - _Requirements: 11.2, 12.2_

- [x] B8. Create `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/BedrockInsightAdvisor.java`
  - Annotate with `@Component` and `@Profile("bedrock")`
  - Implement `InsightAdvisor` with the same system prompt, `AnalysisResult` entity
    mapping, and `clampRiskScore` logic as `OllamaInsightAdvisor`
  - Inject `ChatClient.Builder` via constructor
  - Annotate `analyze(PortfolioDto portfolio)` with
    `@Cacheable(value = "portfolio-analysis", key = "#portfolio.id()")`
  - Short-circuit on empty holdings: return `new AnalysisResult(0, List.of(), List.of())`
    without calling `ChatClient` (same contract as `OllamaInsightAdvisor`)
  - Follow the same error-handling pattern as `OllamaInsightAdvisor`
  - _Requirements: 11.3, 12.3_

- [x] B9. Delete `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/MockBedrockAiInsightService.java`
  - This file is replaced by the real `BedrockAiInsightService` created in B7
  - Both classes are `@Profile("bedrock")` — only one can exist at a time
  - _Requirements: 11.2_

- [x] B10. Remove the Ollama local-dev path entirely (supersedes the earlier "retain with comment" plan)
  - Delete `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/OllamaAiInsightService.java`
  - Delete `insight-service/src/main/java/com/wealth/insight/infrastructure/ai/OllamaInsightAdvisor.java`
  - Delete `insight-service/src/main/resources/application-ollama.yml`
  - Delete `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/OllamaAiInsightServicePropertyTest.java`
    (property-4 `buildPrompt` coverage migrated to `BedrockAiInsightServicePropertyTest`)
  - Relax `@Profile("!ollama & !bedrock")` to `@Profile("!bedrock")` on `MockAiInsightService` and `MockInsightAdvisor`
  - Reason: the Ollama starter (`spring-ai-starter-model-ollama`) was dropped from `build.gradle` in B5.1,
    which left the Ollama Java classes unable to resolve a `ChatClient` bean at startup if the
    `ollama` profile was ever activated (no qualifying ChatModel on the classpath). Keeping the
    classes-only shell was net-negative: dead code that crashed if exercised.
  - _Requirements: 11.6_

- [x] B11. Checkpoint — verify application code compiles and unit tests pass
  - Run `./gradlew :insight-service:compileJava` to confirm no compilation errors
  - Run `./gradlew :portfolio-service:compileJava` to confirm Dockerfile change has no
    impact on build
  - Run `./gradlew :insight-service:test` to confirm existing tests still pass
  - Ask the user if questions arise before continuing.

---

### Phase C — CI/CD Pipeline (only after Phase A Terraform is applied)

- [-] C1. Update `.github/workflows/terraform.yml` — add image URI vars, remove S3/layer vars
  - [x] C1.1 Add `TF_VAR_*_image_uri` environment variables to the workflow-level `env` block
    - Add `TF_VAR_portfolio_image_uri` constructed as
      `${{ secrets.AWS_ACCOUNT_ID }}.dkr.ecr.${{ secrets.AWS_REGION }}.amazonaws.com/${{ secrets.ECR_REPOSITORY_NAME_PORTFOLIO }}:latest`
    - Add `TF_VAR_market_data_image_uri` using `ECR_REPOSITORY_NAME_MARKET_DATA`
    - Add `TF_VAR_insight_image_uri` using `ECR_REPOSITORY_NAME_INSIGHT`
    - _Requirements: 1.7, 3.4, 10.3_
  - [x] C1.2 Remove obsolete S3 and layer variables from the `terraform plan` step
    - Remove `-var="s3_key_portfolio=..."`, `-var="s3_key_market_data=..."`,
      `-var="s3_key_insight=..."`, and `-var="lambda_adapter_layer_arn=..."` arguments
    - _Requirements: 8.2, 8.3_
  - [x] C1.3 Remove obsolete `TF_VAR_*` entries from the workflow-level `env` block
    - Remove `TF_VAR_*` entries for `S3_KEY_PORTFOLIO`, `S3_KEY_MARKET_DATA`,
      `S3_KEY_INSIGHT`, and `LAMBDA_ADAPTER_LAYER_ARN` if present
    - _Requirements: 8.3_

- [-] C2. Update `.github/workflows/deploy.yml` — extend `deploy-backend` to build and deploy all four services
  - [x] C2.1 Rename the existing api-gateway Lambda update step to use `LAMBDA_FUNCTION_NAME_API_GATEWAY`
    - Replace `LAMBDA_FUNCTION_NAME: ${{ secrets.LAMBDA_FUNCTION_NAME }}` with
      `LAMBDA_FUNCTION_NAME: ${{ secrets.LAMBDA_FUNCTION_NAME_API_GATEWAY }}`
    - Update the secrets comment block at the top of the file to reflect the new secret name
    - _Requirements: 4.6, 7.2, 10.2_
  - [x] C2.2 Add build-push-update steps for `portfolio-service`
    - Add "Build and push portfolio-service image" step using
      `ECR_REPOSITORY: ${{ secrets.ECR_REPOSITORY_NAME_PORTFOLIO }}` and
      `-f portfolio-service/Dockerfile`
    - Add "Update portfolio-service Lambda" step using
      `LAMBDA_FUNCTION_NAME: ${{ secrets.LAMBDA_FUNCTION_NAME_PORTFOLIO }}`
    - Include the same `LastUpdateStatus` polling loop as the api-gateway step
    - Tag with both `:latest` and `:<github.sha>`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 4.9_
  - [x] C2.3 Add build-push-update steps for `market-data-service`
    - Same pattern as C2.2 using `ECR_REPOSITORY_NAME_MARKET_DATA`,
      `LAMBDA_FUNCTION_NAME_MARKET_DATA`, and `-f market-data-service/Dockerfile`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 4.9_
  - [x] C2.4 Add build-push-update steps for `insight-service`
    - Same pattern as C2.2 using `ECR_REPOSITORY_NAME_INSIGHT`,
      `LAMBDA_FUNCTION_NAME_INSIGHT`, and `-f insight-service/Dockerfile`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 4.9_

- [x] C3. Document required GitHub Actions secrets (do not automate — manual repo configuration)
  - Add a comment block at the top of `deploy.yml` listing all new secrets:
    - `ECR_REPOSITORY_NAME_PORTFOLIO` → `wealth-portfolio-service`
    - `ECR_REPOSITORY_NAME_MARKET_DATA` → `wealth-market-data-service`
    - `ECR_REPOSITORY_NAME_INSIGHT` → `wealth-insight-service`
    - `LAMBDA_FUNCTION_NAME_API_GATEWAY` → `wealth-api-gateway`
    - `LAMBDA_FUNCTION_NAME_PORTFOLIO` → `wealth-portfolio-service`
    - `LAMBDA_FUNCTION_NAME_MARKET_DATA` → `wealth-market-data-service`
    - `LAMBDA_FUNCTION_NAME_INSIGHT` → `wealth-insight-service`
  - _Requirements: 10.1, 10.2, 10.4_

---

### Phase D — Property-Based Tests

- [ ] D1. Create `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/SentimentCacheHitTest.java`
  - Use jqwik `@Property` with a generator for arbitrary non-blank ticker strings
    (alphanumeric, 1–10 chars) — minimum 100 tries
  - Configure a Spring test slice with `@EnableCaching` and a `SimpleCacheManager`
    (no Redis required in tests)
  - Mock `ChatClient` to return a fixed string on the first call and throw
    `RuntimeException` on any subsequent call
  - Assert: second `getSentiment(ticker)` call returns the cached value without
    invoking `ChatClient` a second time
  - Tag: `// Feature: lambda-service-split, Property 1: sentiment cache hit avoids Bedrock call`
  - _Requirements: 12.2_

- [ ] D2. Create `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/PortfolioAnalysisCacheHitTest.java`
  - Use jqwik `@Property` with a generator for arbitrary portfolio IDs (UUID strings)
    and `PortfolioDto` instances with non-empty holdings
  - Same caching test slice setup as D1
  - Mock `ChatClient` to return a fixed `AnalysisResult` on first call, throw on second
  - Assert: second `analyze(portfolio)` call returns the cached result without invoking
    `ChatClient` a second time
  - Tag: `// Feature: lambda-service-split, Property 2: portfolio analysis cache hit avoids Bedrock call`
  - _Requirements: 12.3_

- [ ] D3. Create `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/RedisFallThroughTest.java`
  - Use jqwik `@Property` with generators for arbitrary non-blank ticker strings and
    non-null portfolio IDs
  - Configure `RedisConnectionFactory` to throw `RedisConnectionFailureException` on
    every operation (simulates Redis unavailability)
  - Assert: both `getSentiment(ticker)` and `analyze(portfolio)` complete without
    throwing an exception; result is non-null
  - Assert: a WARN-level log entry is emitted for the cache error
  - Tag: `// Feature: lambda-service-split, Property 3: Redis unavailability falls through gracefully`
  - _Requirements: 12.4_

- [ ] D4. Create `insight-service/src/test/java/com/wealth/insight/infrastructure/ai/EmptyPortfolioZeroRiskTest.java`
  - Use jqwik `@Property` with a `@Provide` method generating `PortfolioDto` instances
    with empty holdings lists and varying portfolio IDs
  - No `ChatClient` mock needed — empty portfolio short-circuits before any AI call
  - Assert: `BedrockInsightAdvisor.analyze(portfolio).riskScore() == 0`
  - Assert: `concentrationWarnings` and `rebalancingSuggestions` are both empty lists
  - Tag: `// Feature: lambda-service-split, Property 4: empty portfolio returns zero risk score`
  - _Requirements: 11.3_

- [ ] D5. Checkpoint — run property-based tests
  - Run `./gradlew :insight-service:test` to execute all unit and property-based tests
  - Confirm all four property tests pass with at least 100 iterations each
  - Ask the user if questions arise before continuing.

---

### Phase E — Verification

- [ ] E1. Enhance `infrastructure/terraform/scripts/assert_plan.py` with Image package type and insight profile assertions
  - Add `assert_lambda_image_package_type(changes)` function:
    - For `wealth-portfolio-service`, `wealth-market-data-service`, and
      `wealth-insight-service`, assert `package_type == "Image"`
    - Assert none of these three functions have `runtime`, `handler`, `s3_bucket`,
      or `s3_key` attributes set in the `after` block
  - Update `assert_spring_profiles_active` to allow `"prod,aws,bedrock"` for
    `wealth-insight-service` (currently the function asserts `"prod,aws"` for all
    Lambdas — add a per-function exception for insight)
  - Add `assert_ecr_repositories(changes)` function:
    - Assert three `aws_ecr_repository` resources exist with names
      `wealth-portfolio-service`, `wealth-market-data-service`, `wealth-insight-service`
  - Wire all new assertion functions into `main()`
  - _Requirements: 1.1, 1.2, 3.1, 11.4_

- [ ] E2. Run `terraform validate` against the updated Terraform configuration
  - Run `terraform fmt -check -recursive` in `infrastructure/terraform/`
  - Run `terraform validate` (requires `terraform init` with backend config)
  - Confirm no variable reference errors or missing attribute errors
  - _Requirements: 1.1–1.7, 3.1–3.3, 5.1–5.4, 8.1–8.3_

- [ ] E3. Final checkpoint — all tests pass and artifacts are ready
  - Run `./gradlew :insight-service:test` one final time to confirm all tests green
  - Run `./gradlew :portfolio-service:compileJava` to confirm Dockerfile-only change
    has no build impact
  - Confirm `assert_plan.py` exits 0 against a local `terraform plan` output
  - Ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- **Phase A must be applied (not just planned) before Phase C is merged** — ECR repos
  must exist in AWS state before `deploy.yml` pushes images to them
- Phase B can be developed in parallel with Phase A on a feature branch
- The `redis/` directory in `insight-service/src/main/java/com/wealth/insight/infrastructure/`
  already exists but is empty — `CacheConfig.java` goes there (task B6)
- `OllamaAiInsightService` and `OllamaInsightAdvisor` have been **removed** (see B10).
  Local dev now uses the mock adapters exclusively; a future PR may introduce a
  managed-third-party local AI path (no Docker-packaged LLM sidecar).
- `MockBedrockAiInsightService` is **deleted** in task B9 — it is replaced by the real
  `BedrockAiInsightService` which takes over the `bedrock` profile slot
- New GitHub Actions secrets must be added manually in the repository settings before
  the updated `deploy.yml` is merged (see task C3 for the full list)
- Property tests use jqwik 1.9.2 — added to `insight-service/build.gradle` in task B5.2
