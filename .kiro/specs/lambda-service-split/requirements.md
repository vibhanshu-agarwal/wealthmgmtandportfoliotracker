# Requirements Document

## Introduction

This feature splits the current single deployed Lambda (`wealth-mgmt-backend-lambda`, running only the api-gateway service) into four independently deployed Lambda functions — one per microservice: `wealth-api-gateway`, `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service`. All four functions must use the Image package type with Spring Boot AOT compilation for cold-start performance. The split requires changes across four areas: Dockerfile fixes, Terraform compute module refactoring, ECR repository provisioning, and CI/CD pipeline extension.

## Glossary

- **API_Gateway**: The `wealth-api-gateway` Lambda function running the Spring Cloud Gateway service (`api-gateway` Gradle module).
- **Portfolio_Service**: The `wealth-portfolio-service` Lambda function running the portfolio microservice (`portfolio-service` Gradle module).
- **Market_Data_Service**: The `wealth-market-data-service` Lambda function running the market data microservice (`market-data-service` Gradle module).
- **Insight_Service**: The `wealth-insight-service` Lambda function running the AI insights microservice (`insight-service` Gradle module).
- **Compute_Module**: The Terraform module at `infrastructure/terraform/modules/compute/`.
- **Deploy_Pipeline**: The GitHub Actions workflow defined in `.github/workflows/deploy.yml`.
- **Terraform_Pipeline**: The GitHub Actions workflow defined in `.github/workflows/terraform.yml`.
- **ECR_Repository**: An Amazon Elastic Container Registry repository that stores Docker images for a Lambda function.
- **Function_URL**: An AWS Lambda Function URL — an HTTPS endpoint attached to a Lambda alias that enables direct HTTP invocation without an API Gateway.
- **Image_Lambda**: A Lambda function with `package_type = "Image"` that is deployed from a container image stored in ECR.
- **Zip_Lambda**: A Lambda function with `package_type = "Zip"` that is deployed from a JAR artifact stored in S3.
- **AOT**: Spring Boot Ahead-of-Time compilation (`-Dspring.aot.enabled=true`), which pre-generates reflection metadata to reduce cold-start time.
- **LWA**: AWS Lambda Web Adapter — a sidecar binary at `/opt/extensions/lambda-adapter` that bridges HTTP and the Lambda invocation protocol.
- **Two_Phase_Apply**: A Terraform apply pattern where downstream Function URLs are not available until after the first apply; a second apply wires them into the api-gateway environment variables.
- **Monolith_Lambda**: The legacy `wealth-mgmt-backend-lambda` function that currently runs only the api-gateway service and must be decommissioned after the split is complete.

---

## Requirements

### Requirement 1: Convert Portfolio, Market-Data, and Insight Lambdas to Image Package Type

**User Story:** As a platform engineer, I want all four Lambda functions to use the Image package type, so that each service is deployed consistently using its existing multi-stage Dockerfile and AOT-compiled container image.

#### Acceptance Criteria

1. THE Compute_Module SHALL define `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service` as Image_Lambdas with `package_type = "Image"`.
2. THE Compute_Module SHALL remove the `runtime`, `handler`, `s3_bucket`, `s3_key`, and `layers` attributes from the `aws_lambda_function` resources for `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service`.
3. THE Compute_Module SHALL accept `portfolio_image_uri`, `market_data_image_uri`, and `insight_image_uri` input variables of type `string`, each holding the full ECR image URI for the respective service.
4. THE Compute_Module SHALL set `image_uri` on each of the three converted Lambda resources to the corresponding image URI variable.
5. THE Compute_Module SHALL apply a `lifecycle { ignore_changes = [image_uri] }` block to each of the three converted Lambda resources, consistent with the existing `wealth-api-gateway` pattern, so that deploy.yml controls image updates without Terraform drift.
6. THE Compute_Module SHALL remove the `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, and `lambda_adapter_layer_arn` input variables once no Zip_Lambda resources reference them.
7. THE Terraform*Pipeline SHALL pass `TF_VAR_portfolio_image_uri`, `TF_VAR_market_data_image_uri`, and `TF_VAR_insight_image_uri` to Terraform using the pattern `${{ secrets.AWS_ACCOUNT_ID }}.dkr.ecr.${{ secrets.AWS_REGION }}.amazonaws.com/${{ secrets.ECR_REPOSITORY_NAME*<SERVICE> }}:latest`.

---

### Requirement 2: Fix Portfolio-Service Dockerfile AOT Flag

**User Story:** As a platform engineer, I want the portfolio-service container image to start with AOT enabled, so that its cold-start time is consistent with the other three services.

#### Acceptance Criteria

1. THE Portfolio_Service Dockerfile SHALL include `-Dspring.aot.enabled=true` as a JVM argument in the `ENTRYPOINT` instruction of the runtime stage.
2. WHEN the portfolio-service image is built and run, THE Portfolio_Service SHALL start with the AOT initializer classes active, matching the ENTRYPOINT pattern used by `api-gateway/Dockerfile`, `market-data-service/Dockerfile`, and `insight-service/Dockerfile`.

---

### Requirement 3: Provision ECR Repositories for the Three New Services

**User Story:** As a platform engineer, I want dedicated ECR repositories for portfolio-service, market-data-service, and insight-service, so that each service's container images are stored and versioned independently.

#### Acceptance Criteria

1. THE Terraform_Pipeline SHALL provision three `aws_ecr_repository` resources: one each for `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service`.
2. WHEN an ECR repository is created, THE Terraform_Pipeline SHALL enable `image_tag_mutability = "MUTABLE"` and `force_delete = true` on each repository, consistent with the existing api-gateway ECR repository configuration.
3. THE Terraform_Pipeline SHALL output the repository URLs for all three new ECR repositories so that the Deploy_Pipeline can reference them.
4. THE Compute*Module SHALL accept the three new ECR repository name secrets (`ECR_REPOSITORY_NAME_PORTFOLIO`, `ECR_REPOSITORY_NAME_MARKET_DATA`, `ECR_REPOSITORY_NAME_INSIGHT`) as GitHub Actions secrets and pass them to Terraform via `TF_VAR*\*` environment variables.

---

### Requirement 4: Extend CI/CD Pipeline to Build and Deploy All Four Service Images

**User Story:** As a platform engineer, I want the deploy pipeline to build and push container images for all four services and update each Lambda's function code, so that every push to `main` deploys the complete system.

#### Acceptance Criteria

1. THE Deploy_Pipeline SHALL build and push a container image for each of the four services (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) on every push to `main` or `architecture/cloud-native-extraction`.
2. WHEN building each service image, THE Deploy_Pipeline SHALL use the service-specific Dockerfile (e.g. `portfolio-service/Dockerfile`) with the repository root as the build context.
3. THE Deploy_Pipeline SHALL push each image to its dedicated ECR repository using the secrets `ECR_REPOSITORY_NAME`, `ECR_REPOSITORY_NAME_PORTFOLIO`, `ECR_REPOSITORY_NAME_MARKET_DATA`, and `ECR_REPOSITORY_NAME_INSIGHT`.
4. THE Deploy_Pipeline SHALL tag each image with both `:latest` and `:<github.sha>`.
5. AFTER pushing each image, THE Deploy_Pipeline SHALL call `aws lambda update-function-code --image-uri` for the corresponding Lambda function, waiting for `LastUpdateStatus = Successful` before proceeding to the next update.
6. THE Deploy_Pipeline SHALL target `wealth-api-gateway` (the Terraform-managed function name) when updating the api-gateway Lambda, replacing all references to the legacy `LAMBDA_FUNCTION_NAME` secret that pointed to `wealth-mgmt-backend-lambda`.
7. THE Deploy_Pipeline SHALL use dedicated secrets `LAMBDA_FUNCTION_NAME_PORTFOLIO`, `LAMBDA_FUNCTION_NAME_MARKET_DATA`, and `LAMBDA_FUNCTION_NAME_INSIGHT` for the three new Lambda update steps.
8. IF a `aws lambda update-function-code` call returns a `Failed` status, THEN THE Deploy_Pipeline SHALL exit with a non-zero code and halt the deployment.
9. THE Deploy_Pipeline SHALL NOT call `aws lambda update-function-configuration` — all environment variable management remains exclusively owned by the Terraform_Pipeline.

---

### Requirement 5: Wire Service-to-Service Function URLs in Terraform

**User Story:** As a platform engineer, I want api-gateway's environment variables to point to the correct Lambda Function URLs for the three downstream services, so that all routed requests reach the correct Lambda after the split.

#### Acceptance Criteria

1. THE Compute_Module SHALL set `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, and `INSIGHT_SERVICE_URL` in the `wealth-api-gateway` Lambda environment to the Function URLs of `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service` respectively.
2. WHEN `var.portfolio_function_url`, `var.market_data_function_url`, or `var.insight_function_url` are non-empty strings, THE Compute_Module SHALL use those override values instead of the computed `aws_lambda_function_url.*` outputs, supporting the Two_Phase_Apply pattern.
3. THE Compute_Module SHALL set `PORTFOLIO_SERVICE_URL` in the `wealth-insight-service` Lambda environment to the Function URL of `wealth-portfolio-service`, so that insight-service can call portfolio-service for portfolio context.
4. WHEN all four Lambda functions and their Function URLs are created in a single Terraform apply, THE Compute_Module SHALL resolve the Function URL references without requiring manual variable overrides.

---

### Requirement 6: Fix insight-service Redis Configuration Consistency

**User Story:** As a platform engineer, I want insight-service to use the same Redis connection environment variable as the other services, so that Terraform can inject a single `REDIS_URL` value consistently across all services.

#### Acceptance Criteria

1. THE Insight_Service `application.yml` SHALL configure the Redis connection using `${REDIS_URL:redis://localhost:6379}` via `spring.data.redis.url`, replacing the current `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT` properties.
2. WHEN `REDIS_URL` is set in the Lambda environment, THE Insight_Service SHALL connect to Redis using that URL without requiring `SPRING_DATA_REDIS_HOST` or `SPRING_DATA_REDIS_PORT` to be set.
3. THE Insight_Service `application-prod.yml` SHALL remain unchanged, as it already uses `REDIS_URL` correctly.
4. THE Compute_Module SHALL inject `REDIS_URL` into the `wealth-insight-service` Lambda environment using the existing `local.runtime_secrets.REDIS_URL` value, consistent with how it is injected into api-gateway and portfolio-service.

---

### Requirement 7: Decommission the Monolith Lambda

**User Story:** As a platform engineer, I want the legacy `wealth-mgmt-backend-lambda` function removed once all four services are live, so that there is no ambiguity about which Lambda serves production traffic.

#### Acceptance Criteria

1. WHEN all four Image_Lambdas (`wealth-api-gateway`, `wealth-portfolio-service`, `wealth-market-data-service`, `wealth-insight-service`) are successfully deployed and their health checks pass, THE Terraform_Pipeline SHALL remove the `wealth-mgmt-backend-lambda` resource from Terraform state and destroy the function.
2. THE Deploy_Pipeline SHALL replace the `LAMBDA_FUNCTION_NAME` secret reference (which pointed to `wealth-mgmt-backend-lambda`) with `LAMBDA_FUNCTION_NAME_API_GATEWAY` pointing to `wealth-api-gateway` for all api-gateway update steps.
3. IF the `wealth-mgmt-backend-lambda` function does not exist in AWS at the time of decommission, THEN THE Terraform_Pipeline SHALL treat the missing resource as a no-op and continue without error.

---

### Requirement 8: Remove Obsolete S3 Artifact Variables from Terraform and CI/CD

**User Story:** As a platform engineer, I want all Zip-Lambda S3 artifact variables removed from Terraform and the CI/CD pipelines once the Image migration is complete, so that the configuration does not reference deployment artifacts that no longer exist.

#### Acceptance Criteria

1. THE Compute_Module SHALL remove the `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, and `lambda_adapter_layer_arn` variable declarations once no `aws_lambda_function` resource references them.
2. THE Terraform_Pipeline SHALL remove the `-var="s3_key_portfolio=..."`, `-var="s3_key_market_data=..."`, `-var="s3_key_insight=..."`, and `-var="lambda_adapter_layer_arn=..."` arguments from the `terraform plan` and `terraform apply` steps.
3. THE Terraform*Pipeline SHALL remove the `TF_VAR*\*`environment variable entries for`S3_KEY_PORTFOLIO`, `S3_KEY_MARKET_DATA`, `S3_KEY_INSIGHT`, and `LAMBDA_ADAPTER_LAYER_ARN` secrets.
4. THE Terraform_Pipeline SHALL retain the `artifact_bucket_name` variable and the `aws_s3_bucket.artifacts` resource, as the S3 bucket may be repurposed for other artifacts in future phases.

---

### Requirement 9: Document Spring Profile Behavior on Lambda

**User Story:** As a platform engineer, I want the `local` profile loading behavior on Lambda to be documented, so that future engineers understand why `local,prod,aws` appears in logs and do not attempt to suppress it.

#### Acceptance Criteria

1. THE Compute_Module SHALL include an inline comment on the `SPRING_PROFILES_ACTIVE = "prod,aws"` environment variable explaining that Spring Boot includes the base `application.yml` default profile (`local`) alongside the active profiles, and that this is expected behavior — not a misconfiguration.
2. THE Insight_Service `application.yml` SHALL include an inline comment on the `spring.profiles.active` property noting that the `local` fallback is intentional and is overridden to `prod,aws` by the Lambda environment variable at runtime.

---

### Requirement 10: Add Required GitHub Actions Secrets

**User Story:** As a platform engineer, I want all required GitHub Actions secrets documented and provisioned, so that the Deploy_Pipeline and Terraform_Pipeline can reference ECR repositories and Lambda function names for all four services without missing secret errors.

#### Acceptance Criteria

1. THE Deploy_Pipeline SHALL reference the following new GitHub Actions secrets for ECR repositories: `ECR_REPOSITORY_NAME_PORTFOLIO`, `ECR_REPOSITORY_NAME_MARKET_DATA`, `ECR_REPOSITORY_NAME_INSIGHT`.
2. THE Deploy_Pipeline SHALL reference the following new GitHub Actions secrets for Lambda function names: `LAMBDA_FUNCTION_NAME_API_GATEWAY`, `LAMBDA_FUNCTION_NAME_PORTFOLIO`, `LAMBDA_FUNCTION_NAME_MARKET_DATA`, `LAMBDA_FUNCTION_NAME_INSIGHT`.
3. THE Terraform_Pipeline SHALL reference `TF_VAR_portfolio_image_uri`, `TF_VAR_market_data_image_uri`, and `TF_VAR_insight_image_uri` constructed from the corresponding ECR repository name secrets.
4. WHEN a required secret is absent, THE Deploy_Pipeline SHALL fail at the step that references it with a clear error, rather than silently deploying to the wrong function or repository.

---

### Requirement 11: Replace Ollama with AWS Bedrock (Claude Haiku 4.5) in insight-service

**User Story:** As a platform engineer, I want insight-service to call AWS Bedrock (Claude Haiku 4.5) instead of Ollama for AI inference, so that the application is demo-ready on AWS Lambda without requiring a local LLM sidecar.

**Background:** insight-service originally had three profile-scoped AI adapters: `MockAiInsightService` (default), `OllamaAiInsightService` (profile: `ollama`), and `MockBedrockAiInsightService` (profile: `bedrock` — a placeholder stub). Ollama is a local inference server that cannot run on Lambda and has been removed entirely (see Req 11.6 below) — the `ollama` profile is no longer declared. Claude Haiku 4.5 is chosen over Sonnet for cost efficiency with comparable quality for portfolio insight generation. On Bedrock, Haiku 4.5 is invoked exclusively through the US cross-region system inference profile (`us.anthropic.claude-haiku-4-5-20251001-v1:0`) — direct foundation-model invocation returns `ValidationException` for this family. The Terraform IAM policy grants `bedrock:InvokeModel` + `bedrock:InvokeModelWithResponseStream` on the inference-profile ARN **and** on the foundation-model ARN in each region the profile can route to (us-east-1, us-east-2, us-west-2).

#### Acceptance Criteria

1. THE Insight_Service `build.gradle` SHALL replace `spring-ai-starter-model-ollama` with `spring-ai-starter-model-bedrock-converse`, with a comment explaining the removal: Ollama is a local inference server incompatible with Lambda; Bedrock Converse API provides the same Spring AI `ChatClient` abstraction with AWS-native invocation.
2. THE Insight_Service SHALL implement `BedrockAiInsightService` (replacing `MockBedrockAiInsightService`) annotated `@Profile("bedrock")`, using Spring AI `ChatClient` backed by the Bedrock Converse model `us.anthropic.claude-haiku-4-5-20251001-v1:0` (US cross-region inference profile).
3. THE Insight_Service SHALL implement `BedrockInsightAdvisor` annotated `@Profile("bedrock")`, using the same system prompt and `AnalysisResult` entity mapping as `OllamaInsightAdvisor`, backed by the Bedrock Converse model.
4. THE Compute_Module SHALL set `SPRING_PROFILES_ACTIVE = "prod,aws,bedrock"` on the `wealth-insight-service` Lambda environment, activating the Bedrock adapters at runtime.
5. THE Insight_Service `application-bedrock.yml` SHALL configure the Bedrock model ID: `spring.ai.bedrock.converse.chat.options.model: us.anthropic.claude-haiku-4-5-20251001-v1:0` and region: `spring.ai.bedrock.aws.region: ${AWS_REGION:us-east-1}`. `application-aws.yml` SHALL NOT re-declare the model (single source of truth lives in the profile named after the concern).
6. THE `OllamaAiInsightService`, `OllamaInsightAdvisor`, their property test, and `application-ollama.yml` SHALL be deleted. Local development relies exclusively on `MockAiInsightService` / `MockInsightAdvisor`; a future PR may reintroduce a local AI path using a managed third-party provider rather than a Docker-packaged LLM sidecar. _(Superseded: earlier drafts of this requirement said the Ollama adapters must be retained; the Ollama starter was removed from `build.gradle` and the remaining Java classes depended on an auto-configured Ollama `ChatClient` that is no longer on the classpath, leaving them as dead code.)_
7. WHEN the `bedrock` profile is not active (e.g. local dev, CI), THE Insight_Service SHALL fall back to `MockAiInsightService` (guarded by `@Profile("!bedrock")`) and `MockInsightAdvisor` (guarded by `@Profile("!bedrock")`) with no Bedrock API calls made.

---

### Requirement 12: Cache Bedrock Responses in Redis

**User Story:** As a platform engineer, I want insight-service to cache Bedrock sentiment and portfolio analysis responses in Redis, so that repeated identical requests return instantly without incurring Bedrock API costs.

**Background:** Redis is already wired into insight-service (`spring-boot-starter-data-redis` in `build.gradle`, `REDIS_URL` injected by Terraform). Spring's `@Cacheable` with a Redis `CacheManager` provides this with minimal code. Cache TTL should be long enough to be useful (market sentiment doesn't change minute-to-minute) but short enough to stay fresh (1 hour for sentiment, 30 minutes for portfolio analysis).

#### Acceptance Criteria

1. THE Insight_Service SHALL enable Spring caching (`@EnableCaching`) in a configuration class, backed by a Redis `RedisCacheManager` using the `REDIS_URL`-connected `RedisConnectionFactory`.
2. THE `BedrockAiInsightService.getSentiment(ticker)` method SHALL be annotated `@Cacheable(value = "sentiment", key = "#ticker")` with a TTL of 60 minutes, so that repeated sentiment requests for the same ticker return the cached response without calling Bedrock.
3. THE `BedrockInsightAdvisor.analyze(portfolio)` method SHALL be annotated `@Cacheable(value = "portfolio-analysis", key = "#portfolio.id()")` with a TTL of 30 minutes, so that repeated analysis requests for the same portfolio return the cached response without calling Bedrock.
4. WHEN Redis is unavailable, THE Insight_Service SHALL fall through to Bedrock without throwing an exception — cache failures SHALL be logged at WARN level and treated as cache misses.
5. THE `MockAiInsightService` SHALL NOT be annotated with `@Cacheable` — caching applies only to the Bedrock adapters where API cost is a concern. (The previous Ollama adapters have been removed per Req 11.6.)
6. THE Insight_Service `application-prod.yml` SHALL configure the Redis cache with `spring.cache.type: redis` and define TTLs for the `sentiment` and `portfolio-analysis` cache names.
7. THE Insight_Service `application.yml` (local/default) SHALL configure `spring.cache.type: simple` (in-memory Caffeine/ConcurrentHashMap) so that local development and CI do not require a Redis connection for caching.
