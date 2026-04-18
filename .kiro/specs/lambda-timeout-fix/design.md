# Lambda Timeout Fix — Bugfix Design

## Overview

All four Lambda functions (api-gateway, portfolio-service, market-data-service, insight-service) fail to initialize and time out when deployed to AWS Lambda. The root causes are a combination of seven distinct misconfigurations spanning Docker ENTRYPOINT errors, port mismatches, insufficient timeouts, missing async init, invalid SnapStart handlers, hardcoded downstream URLs requiring manual entry, and missing readiness check paths. This design formalizes each bug condition, defines the expected correct behavior, and outlines a minimal, targeted fix across Dockerfiles, Terraform modules, and the CI/CD pipeline.

## Glossary

- **Bug_Condition (C)**: The set of misconfigurations that cause Lambda functions to time out or fail initialization — incorrect ENTRYPOINT, port mismatch, low timeout, missing async init, invalid SnapStart handler, blank downstream URLs, wrong readiness path
- **Property (P)**: The desired behavior — all four Lambda functions complete cold start within the timeout window, the Lambda Web Adapter detects the Spring Boot app as ready, downstream service URLs are programmatically wired, and SnapStart works correctly for Zip-based Lambdas
- **Preservation**: Existing behaviors that must remain unchanged — warm invocation latency, local Docker Compose execution, portfolio-service and market-data-service Dockerfile ENTRYPOINT patterns, CI/CD pipeline structure, IAM roles, Function URLs, aliases, VPC configuration
- **Lambda Web Adapter (LWA)**: AWS-provided extension that bridges Lambda's invoke model to HTTP-based web frameworks by proxying Lambda events to a local HTTP server (Spring Boot)
- **ENTRYPOINT**: The Docker instruction that defines the main process. In Lambda container images, the ENTRYPOINT must be the application process; the LWA runs as a sidecar extension from `/opt/extensions/`
- **SnapStart**: AWS Lambda feature that snapshots the JVM after initialization and restores from that snapshot on cold start, reducing init time. Requires a valid handler class
- **AWS_LWA_ASYNC_INIT**: When `true`, the LWA immediately reports init success to the Lambda runtime, allowing Spring Boot startup to continue beyond the 10-second init phase limit
- **Function URL**: An HTTPS endpoint attached to a Lambda function, used for service-to-service communication in this architecture

## Bug Details

### Bug Condition

The bug manifests when any of the four Lambda functions are invoked after deployment. The functions fail to initialize and time out due to seven distinct misconfigurations that interact to prevent successful cold starts.

**Formal Specification:**

```
FUNCTION isBugCondition(deployment)
  INPUT: deployment of type LambdaDeploymentConfig
  OUTPUT: boolean

  hasEntrypointBug :=
    (deployment.service IN ['api-gateway', 'insight-service']
     AND deployment.dockerfile.entrypoint == '/opt/extensions/aws-lambda-web-adapter')

  hasPortMismatch :=
    (deployment.service == 'insight-service'
     AND deployment.dockerfile.AWS_LWA_PORT != deployment.terraform.PORT)

  hasLowTimeout :=
    (deployment.terraform.timeout < 60)

  hasMissingAsyncInit :=
    (deployment.terraform.env['AWS_LWA_ASYNC_INIT'] IS NULL
     OR deployment.terraform.env['AWS_LWA_ASYNC_INIT'] != 'true')

  hasInvalidSnapStart :=
    (deployment.service IN ['portfolio-service', 'market-data-service', 'insight-service']
     AND deployment.terraform.handler == 'not.used'
     AND deployment.terraform.snap_start.apply_on == 'PublishedVersions')

  hasBlankDownstreamUrls :=
    (deployment.service == 'api-gateway'
     AND (deployment.terraform.env['PORTFOLIO_SERVICE_URL'] == ''
          OR deployment.terraform.env['MARKET_DATA_SERVICE_URL'] == ''
          OR deployment.terraform.env['INSIGHT_SERVICE_URL'] == ''))

  hasMissingReadinessPath :=
    (deployment.service IN ['portfolio-service', 'market-data-service', 'insight-service']
     AND deployment.terraform.env['AWS_LWA_READINESS_CHECK_PATH'] IS NULL)

  RETURN hasEntrypointBug
         OR hasPortMismatch
         OR hasLowTimeout
         OR hasMissingAsyncInit
         OR hasInvalidSnapStart
         OR hasBlankDownstreamUrls
         OR hasMissingReadinessPath
END FUNCTION
```

### Examples

- **api-gateway ENTRYPOINT bug**: ENTRYPOINT is `["/opt/extensions/aws-lambda-web-adapter"]` — the adapter binary runs as PID 1 instead of the Java app. Lambda expects the extension to run as a sidecar from `/opt/extensions/`, not as the main process. Result: Spring Boot never starts, function times out.
- **insight-service port mismatch**: Dockerfile sets `AWS_LWA_PORT=8083` and `EXPOSE 8083`, but Terraform sets `PORT=8080` via `common_env`. Spring Boot listens on 8080 (from Terraform's PORT), but the LWA polls 8083 (from Dockerfile ENV). Result: readiness check never succeeds, function times out.
- **30-second timeout**: All four Lambdas have `timeout = 30`. Spring Boot 4 with AOT on 1024 MB memory can take 15-25 seconds to initialize. With the 10-second init phase limit and no async init, the remaining startup eats into the 30-second function timeout. Result: cold starts frequently exceed 30 seconds.
- **Missing async init**: Without `AWS_LWA_ASYNC_INIT=true`, the LWA blocks during the 10-second Lambda init phase waiting for Spring Boot. If Spring Boot isn't ready in 10 seconds, the remaining init time counts against the function timeout. Result: effective timeout is reduced by the init overshoot.
- **Invalid SnapStart handler**: `handler = "not.used"` is not a valid Java class. SnapStart requires a real handler to snapshot the JVM state. Result: SnapStart silently fails, every invocation is a full cold start.
- **Blank downstream URLs**: `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL` default to `""` in Terraform variables. The api-gateway Lambda receives empty strings, causing Spring Cloud Gateway to fail routing. The user had to manually enter these in the AWS Console. Result: after every `terraform apply`, the URLs reset to empty.
- **Missing readiness path**: Zip-based Lambdas (portfolio, market-data, insight) don't have `AWS_LWA_READINESS_CHECK_PATH` set. The LWA defaults to polling `/`, which may not return 200 on all services. Result: readiness check may fail or take longer than necessary.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- portfolio-service and market-data-service Dockerfiles already use the correct ENTRYPOINT pattern (`java -jar`) and must not be modified
- Warm Lambda invocations (no cold start) must continue to handle requests within normal response times
- The CI/CD pipeline (`deploy.yml`) must continue to build, push, and update Lambda functions using the same workflow steps
- Local Docker Compose and Lambda Runtime Interface Emulator execution must continue to work
- IAM roles, Function URLs, aliases, VPC configuration, and all other compute module resources must remain unchanged
- The `TF_VAR_*` override mechanism for downstream URLs must continue to work as an optional override, but should no longer be required

**Scope:**
All inputs that do NOT involve the seven bug conditions should be completely unaffected by this fix. This includes:

- Warm invocations on already-initialized Lambda functions
- Local development via Docker Compose
- Frontend deployment pipeline
- Database module Terraform resources
- CDN/CloudFront module Terraform resources

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are confirmed (not hypothesized) across seven areas:

1. **Incorrect Docker ENTRYPOINT (api-gateway, insight-service)**: Both Dockerfiles set `ENTRYPOINT ["/opt/extensions/aws-lambda-web-adapter"]`, making the adapter the main process. In Lambda container images, the adapter must be placed in `/opt/extensions/` (which both do via `COPY`) and Lambda automatically loads it as an extension sidecar. The ENTRYPOINT should be the Java application. The portfolio-service and market-data-service Dockerfiles already follow the correct pattern: `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`.

2. **Port Mismatch (insight-service)**: The Dockerfile hardcodes `AWS_LWA_PORT=8083` matching the local dev port (`server.port: 8083` in application.yml). But in Lambda, Terraform's `common_env` sets `PORT=8080`, which Spring Boot picks up via `${PORT:8080}`. The LWA polls port 8083 (from Dockerfile ENV) while Spring Boot listens on 8080 (from Terraform ENV). The Dockerfile should not hardcode `AWS_LWA_PORT` — it should be set via Terraform environment variables or removed from the Dockerfile so the LWA defaults to port 8080.

3. **Insufficient Lambda Timeout**: All four functions use `timeout = 30`. Spring Boot 4 with AOT on lower-memory Lambdas (1024 MB) can take 15-25 seconds for cold start. Combined with the init phase overshoot (no async init), 30 seconds is insufficient.

4. **Missing AWS_LWA_ASYNC_INIT**: Without this flag, the LWA blocks during Lambda's 10-second init phase. If Spring Boot takes longer than 10 seconds, the remaining startup time counts against the function timeout, effectively reducing available processing time.

5. **Invalid SnapStart Handler**: The Zip-based Lambdas use `handler = "not.used"` — a non-existent class. SnapStart needs a valid handler to create a JVM snapshot. Since these services use the Lambda Web Adapter pattern (not Spring Cloud Function), SnapStart with a dummy handler provides no benefit. The `snap_start` block should be removed, or a valid handler class should be provided.

6. **Blank Downstream Service URLs**: The Terraform `aws_lambda_function.api_gateway` resource reads `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, and `INSIGHT_SERVICE_URL` from root variables that default to `""`. These should be wired programmatically from the Function URL outputs of the downstream Lambda functions within the same Terraform module. Since all four functions and their Function URLs are defined in the same `modules/compute/main.tf`, the outputs are available as direct resource references.

7. **Missing AWS_LWA_READINESS_CHECK_PATH**: The Zip-based Lambdas (portfolio, market-data, insight) don't set `AWS_LWA_READINESS_CHECK_PATH` in their Terraform environment variables. The LWA defaults to polling `/`, but Spring Boot Actuator's health endpoint at `/actuator/health` is the correct readiness indicator.

## Correctness Properties

Property 1: Bug Condition — Lambda Functions Initialize Successfully

_For any_ deployment where the bug conditions previously held (incorrect ENTRYPOINT, port mismatch, low timeout, missing async init, invalid SnapStart, blank URLs, missing readiness path), the fixed configuration SHALL allow all four Lambda functions to complete cold start initialization within the timeout window, with the Lambda Web Adapter successfully detecting Spring Boot as ready via the `/actuator/health` endpoint, and the api-gateway routing requests to downstream services via programmatically wired Function URLs.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7**

Property 2: Preservation — Unchanged Behaviors After Fix

_For any_ deployment configuration where the bug conditions do NOT hold (warm invocations, local Docker Compose execution, portfolio-service/market-data-service Dockerfile patterns, CI/CD pipeline, IAM/VPC/alias resources), the fixed code SHALL produce exactly the same behavior as the original code, preserving all existing functionality for non-buggy configurations.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**

## Fix Implementation

### Changes Required

**File**: `api-gateway/Dockerfile`

**Change**: Fix ENTRYPOINT to run the Java application as the main process

- Replace `ENTRYPOINT ["/opt/extensions/aws-lambda-web-adapter"]` with `ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]`
- Remove the `CMD` instruction (no longer needed since ENTRYPOINT now runs the app directly)
- The LWA is already correctly placed in `/opt/extensions/` via the `COPY` instruction and will be auto-loaded by Lambda as a sidecar extension

---

**File**: `insight-service/Dockerfile`

**Changes**:

1. Fix ENTRYPOINT to run the Java application as the main process (same pattern as api-gateway fix)
   - Replace `ENTRYPOINT ["/opt/extensions/aws-lambda-web-adapter"]` with `ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]`
   - Remove the `CMD` instruction
2. Remove the hardcoded `AWS_LWA_PORT=8083` — the port should be controlled by Terraform environment variables at deploy time, not baked into the image
3. Remove the hardcoded `AWS_LWA_READINESS_CHECK_PATH=/actuator/health` from the Dockerfile — this will be set via Terraform environment variables for consistency with other services
4. Change `EXPOSE 8083` to `EXPOSE 8080` to match the Lambda runtime port (or keep 8083 for local dev and let Terraform override via PORT)

---

**File**: `infrastructure/terraform/modules/compute/main.tf`

**Changes**:

1. **Increase timeout**: Change `timeout = 30` to `timeout = var.lambda_timeout` on all four Lambda functions, with a default of 60 seconds in the variable definition
2. **Add AWS_LWA_ASYNC_INIT**: Add `AWS_LWA_ASYNC_INIT = "true"` to `common_env` and `api_gateway_container_env` locals
3. **Add AWS_LWA_READINESS_CHECK_PATH**: Add `AWS_LWA_READINESS_CHECK_PATH = "/actuator/health"` to `common_env` and `api_gateway_container_env` locals
4. **Remove SnapStart**: Remove the `snap_start` block from portfolio, market-data, and insight Lambda functions (the `handler = "not.used"` pattern is incompatible with SnapStart)
5. **Wire downstream URLs programmatically**: Replace `var.portfolio_function_url`, `var.market_data_function_url`, `var.insight_function_url` in the api-gateway environment with direct references to `aws_lambda_function_url.portfolio.function_url`, `aws_lambda_function_url.market_data.function_url`, `aws_lambda_function_url.insight.function_url` — using `coalesce()` to still allow TF_VAR overrides
6. **Fix insight-service port**: Add `PORT = "8080"` and `AWS_LWA_PORT = "8080"` to the insight Lambda environment to ensure the LWA and Spring Boot agree on the port

---

**File**: `infrastructure/terraform/modules/compute/variables.tf`

**Change**: Add a `lambda_timeout` variable with a default of 60 seconds

---

**File**: `infrastructure/terraform/locals.tf`

**Change**: Add `lambda_timeout_seconds = 60` to `lambda_defaults`

---

**File**: `.github/workflows/deploy.yml`

**Changes**:

1. Add `AWS_LWA_ASYNC_INIT` and `AWS_LWA_READINESS_CHECK_PATH` to the `lambda-env.json` generation step
2. The downstream URL secrets (`PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL`) can remain as optional overrides in the CI/CD pipeline since Terraform now wires them programmatically

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior. Since this bug involves infrastructure configuration (Dockerfiles, Terraform, CI/CD), testing focuses on static analysis of configuration files and Terraform plan validation rather than runtime property-based testing.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write tests that parse Dockerfiles and Terraform configuration to assert the presence of each bug condition. Run these tests on the UNFIXED code to observe failures and confirm the root causes.

**Test Cases**:

1. **ENTRYPOINT Test (api-gateway)**: Parse `api-gateway/Dockerfile` and assert ENTRYPOINT is NOT the LWA binary (will fail on unfixed code — ENTRYPOINT is `/opt/extensions/aws-lambda-web-adapter`)
2. **ENTRYPOINT Test (insight-service)**: Parse `insight-service/Dockerfile` and assert ENTRYPOINT is NOT the LWA binary (will fail on unfixed code)
3. **Port Mismatch Test**: Assert that `AWS_LWA_PORT` in insight-service Dockerfile matches the `PORT` in Terraform common_env (will fail on unfixed code — 8083 vs 8080)
4. **Timeout Test**: Run `terraform plan` and assert all Lambda timeouts are >= 60 seconds (will fail on unfixed code — all are 30)
5. **Async Init Test**: Assert `AWS_LWA_ASYNC_INIT=true` is present in Lambda environment variables (will fail on unfixed code — not set)
6. **SnapStart Handler Test**: Assert that Lambdas with `snap_start` have a valid handler class (will fail on unfixed code — handler is `not.used`)
7. **Downstream URL Test**: Assert that api-gateway Lambda environment has non-empty downstream URLs from Terraform resource references (will fail on unfixed code — sourced from variables defaulting to `""`)

**Expected Counterexamples**:

- Dockerfile ENTRYPOINT is the LWA binary instead of the Java application
- Port 8083 in Dockerfile does not match port 8080 in Terraform
- Lambda timeout is 30 seconds, below the 60-second minimum for Spring Boot cold starts
- Possible causes confirmed: incorrect ENTRYPOINT, port mismatch, low timeout, missing async init, invalid handler, blank URLs, missing readiness path

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed configuration produces the expected behavior.

**Pseudocode:**

```
FOR ALL deployment WHERE isBugCondition(deployment) DO
  result := applyFixedConfig(deployment)
  ASSERT result.entrypoint == 'java -jar /app/app.jar'
  ASSERT result.lwaPort == result.springBootPort
  ASSERT result.timeout >= 60
  ASSERT result.asyncInit == true
  ASSERT result.snapStart IS REMOVED OR result.handler IS VALID CLASS
  ASSERT result.downstreamUrls ARE NOT EMPTY
  ASSERT result.readinessPath == '/actuator/health'
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed configuration produces the same result as the original configuration.

**Pseudocode:**

```
FOR ALL deployment WHERE NOT isBugCondition(deployment) DO
  ASSERT originalConfig(deployment) = fixedConfig(deployment)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for non-bug configurations (portfolio-service Dockerfile, market-data-service Dockerfile, warm invocations, local dev), then write property-based tests capturing that behavior.

**Test Cases**:

1. **Portfolio Dockerfile Preservation**: Verify portfolio-service Dockerfile ENTRYPOINT remains `["java", "-jar", "/app/app.jar"]` after fix — it should not be modified
2. **Market-Data Dockerfile Preservation**: Verify market-data-service Dockerfile ENTRYPOINT remains `["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]` after fix — it should not be modified
3. **IAM Role Preservation**: Run `terraform plan` and verify no changes to IAM roles, policies, or attachments
4. **Function URL Preservation**: Run `terraform plan` and verify Function URL resources are unchanged
5. **TF_VAR Override Preservation**: Verify that setting `TF_VAR_portfolio_function_url` still overrides the programmatic wiring via `coalesce()`

### Unit Tests

- Parse Dockerfiles and assert correct ENTRYPOINT for each service
- Parse Terraform HCL and assert timeout values, environment variables, and SnapStart configuration
- Validate that `deploy.yml` environment variable JSON includes all required keys
- Assert port consistency between Dockerfile ENV and Terraform environment variables

### Property-Based Tests

- Generate random Lambda deployment configurations and verify that all configurations matching the bug condition are correctly fixed
- Generate random Terraform variable overrides and verify that `coalesce()` correctly prioritizes TF_VAR overrides over programmatic wiring
- Test across many timeout values to verify the timeout variable propagates correctly to all four Lambda functions

### Integration Tests

- Run `terraform plan` on the fixed configuration and verify the plan output shows the expected changes (timeout, env vars, SnapStart removal, URL wiring)
- Build Docker images for api-gateway and insight-service with the fixed Dockerfiles and verify the ENTRYPOINT is correct via `docker inspect`
- Deploy to a test environment and verify all four Lambda functions complete cold start successfully
