# Lambda Environment Variable Ownership Bugfix Design

## Overview

The `deploy.yml` workflow's `jq -n` builder performs a full-replace of the Lambda environment variables map, silently dropping `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`, and `SPRING_DATASOURCE_*`. Additionally, `scripts/sync-secrets.sh --lambda` directly calls `aws lambda update-function-configuration`, creating a third source of drift outside Terraform state. A secondary defect compounds the problem: Terraform's `common_env` sets `SPRING_PROFILES_ACTIVE` to `"aws"` instead of `"prod,aws"`, preventing `application-prod.yml` from loading.

The fix consolidates Lambda environment variable ownership entirely into Terraform by:

1. Adding missing variables (`redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password`) to the Terraform compute module
2. Correcting `SPRING_PROFILES_ACTIVE` from `"aws"` to `"prod,aws"`
3. Removing the `update-function-configuration` step from `deploy.yml` (image-only deploys)
4. Removing the `--lambda` code path from `sync-secrets.sh`
5. Adding dual-branch trigger to `deploy.yml`
6. Updating `.gitignore`, tfvars examples, and documentation

## Glossary

- **Bug_Condition (C)**: Any deployment path (`deploy.yml` run or `sync-secrets.sh --lambda`) that calls `aws lambda update-function-configuration --environment` with a `Variables` map that omits required env vars, performing a full-replace that wipes Terraform-managed state
- **Property (P)**: Terraform is the single owner of the Lambda `Variables` map; all required env vars (`REDIS_URL`, `KAFKA_*`, `SPRING_DATASOURCE_URL`, `SPRING_DATA_MONGODB_URI`, `SPRING_PROFILES_ACTIVE=prod,aws`) are present after `terraform apply`; no other tool modifies the Lambda environment
- **Preservation**: Existing behaviors that must remain unchanged — image build/push/deploy in `deploy.yml`, `terraform.yml` pipeline, `assert_plan.py` checks, api-gateway service URLs and auth vars, `gh secret set` path in `sync-secrets.sh`, frontend deploy job
- **`common_env`**: The `locals` block in `modules/compute/main.tf` that defines shared Lambda environment variables for all Zip-based Lambda functions
- **`api_gateway_container_env`**: The `locals` block in `modules/compute/main.tf` that defines environment variables specific to the api-gateway Image-based Lambda (excludes `AWS_LAMBDA_EXEC_WRAPPER`)
- **Full-replace semantics**: AWS `update-function-configuration --environment` replaces the entire `Variables` map — any key not in the new payload is deleted from the live Lambda

## Bug Details

### Bug Condition

The bug manifests through three independent paths that modify Lambda environment variables outside Terraform state:

1. **deploy.yml**: The `jq -n` builder constructs a `Variables` map with only 11 keys (infrastructure + api-gateway routing), omitting `REDIS_URL`, `KAFKA_*`, and `SPRING_DATASOURCE_*`. The `aws lambda update-function-configuration --environment` call performs a full-replace, wiping all Terraform-managed variables not in the payload.

2. **sync-secrets.sh --lambda**: The `--lambda` flag builds a hardcoded JSON payload and calls `aws lambda update-function-configuration` directly, bypassing Terraform state entirely.

3. **SPRING_PROFILES_ACTIVE = "aws"**: Even when variables are present, the missing `prod` profile means `application-prod.yml` never loads, so Spring Boot cannot resolve `${REDIS_URL}`, `${KAFKA_BOOTSTRAP_SERVERS}`, etc.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input of type DeploymentAction
  OUTPUT: boolean

  // Path 1: deploy.yml full-replace wipe
  IF input.source == "deploy.yml"
     AND input.action == "update-function-configuration"
     AND input.envPayload DOES NOT CONTAIN ALL OF
         ["REDIS_URL", "KAFKA_BOOTSTRAP_SERVERS", "KAFKA_SASL_USERNAME",
          "KAFKA_SASL_PASSWORD", "SPRING_DATASOURCE_URL"]
  THEN RETURN true

  // Path 2: sync-secrets.sh --lambda drift
  IF input.source == "sync-secrets.sh"
     AND input.flags CONTAINS "--lambda"
     AND input.action == "update-function-configuration"
  THEN RETURN true

  // Path 3: incorrect Spring profile
  IF input.source == "terraform"
     AND input.envPayload["SPRING_PROFILES_ACTIVE"] == "aws"
     AND input.envPayload["SPRING_PROFILES_ACTIVE"] != "prod,aws"
  THEN RETURN true

  RETURN false
END FUNCTION
```

### Examples

- **deploy.yml run on `architecture/cloud-native-extraction`**: The `jq -n` builder emits `{"Variables": {"SPRING_PROFILES_ACTIVE": "prod,aws", "SERVER_PORT": "8080", ...}}` with 11 keys. After `update-function-configuration`, `REDIS_URL` is gone → api-gateway falls back to `localhost:6379` → TCP hang → `Extension.Crash`
- **sync-secrets.sh .env.secrets --lambda wealth-api-gateway**: Pushes a hardcoded 11-key JSON to the Lambda, overwriting Terraform's `PORTFOLIO_SERVICE_URL` (which uses Function URL outputs) with `http://localhost:8081` from `.env.secrets`
- **Terraform apply with `SPRING_PROFILES_ACTIVE = "aws"`**: Lambda starts, Spring Boot loads only `application-aws.yml`, skips `application-prod.yml` → `${KAFKA_BOOTSTRAP_SERVERS}` is never resolved → Kafka connection fails even though the env var is present
- **Edge case — first Terraform apply**: `portfolio_function_url` is empty, so api-gateway gets the Function URL output directly. A subsequent `deploy.yml` run replaces this with the GitHub secret value (which may be stale or empty)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- `deploy.yml` SHALL continue to build the api-gateway Docker image, push to ECR, wait for `LastUpdateStatus`, and call `update-function-code` to deploy the new image
- `deploy-frontend` job SHALL continue to build Next.js static export, sync to S3, and invalidate CloudFront
- `terraform.yml` SHALL continue to run `terraform plan`, execute `assert_plan.py`, and apply on push to `main`
- `assert_plan.py` SHALL continue to validate `SPRING_PROFILES_ACTIVE` presence, all four Lambda functions, `reserved_concurrent_executions` bounds, and prohibited resource types
- api-gateway Lambda SHALL continue to have `AUTH_JWK_URI`, `CLOUDFRONT_ORIGIN_SECRET`, `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL` in its environment
- portfolio-service Lambda SHALL continue to have `SPRING_DATASOURCE_URL` (via `var.postgres_connection_string`)
- market-data-service Lambda SHALL continue to have `SPRING_DATA_MONGODB_URI` (via `var.mongodb_connection_string`)
- `sync-secrets.sh` SHALL continue to sync `.env.secrets` to GitHub Actions via `gh secret set -f`
- api-gateway (Image-based) SHALL continue to exclude `AWS_LAMBDA_EXEC_WRAPPER` from its environment while Zip-based Lambdas retain it via `common_env`

**Scope:**
All inputs that do NOT involve Lambda environment variable modification outside Terraform should be completely unaffected by this fix. This includes:

- Image builds and pushes to ECR
- Frontend builds and S3 syncs
- Terraform plan/apply for non-compute resources (S3, CloudFront, IAM, Route 53)
- GitHub Actions secret syncing (the `gh secret set -f` path)
- Local development via Docker Compose

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

1. **Dual ownership of Lambda environment**: `deploy.yml` (lines 137-176) calls `aws lambda update-function-configuration --environment` with a `jq`-built JSON that only includes api-gateway routing vars. AWS's full-replace semantics wipe every variable not in the payload, including `REDIS_URL`, `KAFKA_*`, and `SPRING_DATASOURCE_*` that Terraform manages.

2. **Triple ownership via sync-secrets.sh**: The `--lambda` flag (lines 65-126) builds a hardcoded 11-key JSON and calls `update-function-configuration` directly, creating a third source of truth that can overwrite Terraform-managed Function URLs with localhost stubs from `.env.secrets`.

3. **Incorrect Spring profile value**: `modules/compute/main.tf` line 4 sets `SPRING_PROFILES_ACTIVE = "aws"` in `common_env`. Without `"prod"` in the profile list, Spring Boot skips `application-prod.yml` where `${REDIS_URL}`, `${KAFKA_BOOTSTRAP_SERVERS}`, `${SPRING_DATASOURCE_URL}`, and `${SPRING_DATA_MONGODB_URI}` are referenced. Even if the env vars are present on the Lambda, Spring cannot resolve them.

4. **Missing Terraform variables**: The compute module has `var.postgres_connection_string` and `var.mongodb_connection_string` but lacks `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, and `kafka_sasl_password`. These were never added to Terraform because `deploy.yml` and `sync-secrets.sh --lambda` were the assumed delivery mechanism.

5. **Single-branch trigger**: `deploy.yml` triggers only on `architecture/cloud-native-extraction`, not `main`. After merging to `main`, pushes to `main` do not trigger a deploy, causing the two branches to diverge in deployed state.

## Correctness Properties

Property 1: Bug Condition - Terraform Owns All Required Lambda Environment Variables

_For any_ Terraform plan/apply where the compute module is included, the resulting Lambda environment `Variables` map for each function SHALL contain all required keys: `SPRING_PROFILES_ACTIVE` set to `"prod,aws"`, and service-specific variables (`REDIS_URL` for api-gateway/portfolio/insight, `KAFKA_BOOTSTRAP_SERVERS`/`KAFKA_SASL_USERNAME`/`KAFKA_SASL_PASSWORD` for portfolio/market-data/insight, `SPRING_DATASOURCE_URL` for portfolio, `SPRING_DATA_MONGODB_URI` for market-data). No other tool (`deploy.yml`, `sync-secrets.sh`) SHALL call `update-function-configuration` to modify the Lambda environment.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7**

Property 2: Preservation - Existing Lambda Configuration and Pipeline Behavior

_For any_ deployment action that does NOT involve Lambda environment variable modification (image builds, frontend deploys, Terraform non-compute resources, `gh secret set`), the fixed code SHALL produce exactly the same behavior as the original code, preserving all existing functionality including api-gateway service URLs, auth vars, image deploy flow, frontend deploy flow, `terraform.yml` pipeline, `assert_plan.py` checks, and `sync-secrets.sh` GitHub secret syncing.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `infrastructure/terraform/modules/compute/variables.tf`

**Specific Changes**:

1. **Add `redis_url` variable**: `type = string`, `sensitive = true`, for Redis connection URL used by api-gateway, portfolio-service, and insight-service
2. **Add `kafka_bootstrap_servers` variable**: `type = string`, for Kafka broker address used by portfolio-service, market-data-service, and insight-service
3. **Add `kafka_sasl_username` variable**: `type = string`, `sensitive = true`, for Kafka SASL authentication
4. **Add `kafka_sasl_password` variable**: `type = string`, `sensitive = true`, for Kafka SASL authentication

---

**File**: `infrastructure/terraform/modules/compute/main.tf`

**Specific Changes**:

1. **Fix `SPRING_PROFILES_ACTIVE`**: Change `"aws"` to `"prod,aws"` in both `common_env` and `api_gateway_container_env`
2. **Add `REDIS_URL` to api-gateway, portfolio, and insight Lambda environments**: Merge `var.redis_url` into each function's environment block
3. **Add `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` to portfolio, market-data, and insight Lambda environments**: Merge Kafka vars into each function's environment block
4. **Preserve existing merge pattern**: Continue using `merge(local.common_env, {...})` / `merge(local.api_gateway_container_env, {...})` for service-specific overrides

---

**File**: `infrastructure/terraform/variables.tf`

**Specific Changes**:

1. **Add `redis_url` variable**: `type = string`, `sensitive = true`
2. **Add `kafka_bootstrap_servers` variable**: `type = string`
3. **Add `kafka_sasl_username` variable**: `type = string`, `sensitive = true`
4. **Add `kafka_sasl_password` variable**: `type = string`, `sensitive = true`

---

**File**: `infrastructure/terraform/main.tf`

**Specific Changes**:

1. **Pass new variables to compute module**: Add `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password` to the `module "compute"` block

---

**File**: `.github/workflows/deploy.yml`

**Specific Changes**:

1. **Remove "Update Lambda function configuration" step** (lines 137-176): Delete the entire step that builds `lambda-env.json` via `jq` and calls `update-function-configuration`
2. **Remove "Install jq" step**: No longer needed since the `jq` builder step is removed
3. **Remove wait-for-config-update loop preamble** in the "Update Lambda function image" step: The `LastUpdateStatus` polling loop was needed because `update-function-configuration` preceded `update-function-code`. Without the config update, the code update can proceed directly (keep a simple status check for safety)
4. **Add dual-branch trigger**: Change `branches: [architecture/cloud-native-extraction]` to `branches: [main, architecture/cloud-native-extraction]`
5. **Remove unused secrets from header comments**: Remove `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL`, `AUTH_JWK_URI`, `CLOUDFRONT_ORIGIN_SECRET` from the "Application/runtime secrets" comment block (these are now Terraform-only)

---

**File**: `scripts/sync-secrets.sh`

**Specific Changes**:

1. **Remove `--lambda` flag parsing**: Remove the `--lambda` argument parsing from the `while` loop
2. **Remove Lambda sync section** (lines 65-126): Delete the entire `if [ -n "$LAMBDA_FUNCTION" ]` block that builds `LAMBDA_ENV_JSON` and calls `aws lambda update-function-configuration`
3. **Update header comments**: Remove `--lambda` usage example and Lambda-specific documentation
4. **Simplify script**: Remove `LAMBDA_FUNCTION` variable, `shift` logic, and `trap` cleanup

---

**File**: `.github/workflows/terraform.yml`

**Specific Changes**:

1. **Add `TF_VAR_redis_url`**: Map from `${{ secrets.REDIS_URL }}` GitHub secret
2. **Add `TF_VAR_kafka_bootstrap_servers`**: Map from `${{ secrets.KAFKA_BOOTSTRAP_SERVERS }}`
3. **Add `TF_VAR_kafka_sasl_username`**: Map from `${{ secrets.KAFKA_SASL_USERNAME }}`
4. **Add `TF_VAR_kafka_sasl_password`**: Map from `${{ secrets.KAFKA_SASL_PASSWORD }}`

---

**File**: `infrastructure/terraform/terraform.tfvars.example`

**Specific Changes**:

1. **Add placeholder comments** for `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password` in the sensitive variables section

---

**File**: `infrastructure/terraform/localstack.tfvars`

**Specific Changes**:

1. **Add stub values** for `redis_url`, `kafka_bootstrap_servers`, `kafka_sasl_username`, `kafka_sasl_password` for LocalStack testing

---

**File**: `.gitignore`

**Specific Changes**:

1. **Add `.env.secrets`**: Prevent accidental commit of secrets file
2. **Add `app-inspect.jar`**: Prevent accidental commit of inspection artifact

---

**File**: `.env.secrets.example`

**Specific Changes**:

1. **Add `KAFKA_BOOTSTRAP_SERVERS`**: Placeholder for Kafka broker address
2. **Add `KAFKA_SASL_USERNAME`**: Placeholder for Kafka SASL username
3. **Add `KAFKA_SASL_PASSWORD`**: Placeholder for Kafka SASL password

---

**File**: `infrastructure/terraform/scripts/assert_plan.py`

**Specific Changes**:

1. **Update `assert_spring_profiles_active`**: Optionally validate that `SPRING_PROFILES_ACTIVE` value is `"prod,aws"` (not just present) when the value is known in the plan JSON

---

**File**: `docs/changes/CHANGES_PHASE3_INFRA_SUMMARY_19042026.md`

**Specific Changes**:

1. **Append section**: Document the Lambda env ownership consolidation, deploy.yml changes, sync-secrets.sh simplification, and SPRING_PROFILES_ACTIVE correction

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Analyze the current Terraform configuration and deploy.yml to confirm the missing variables and incorrect profile value. Run `terraform plan` with LocalStack to verify the current state of Lambda environment variables.

**Test Cases**:

1. **Missing REDIS_URL Test**: Run `terraform plan` with current code and inspect the planned Lambda environment for api-gateway, portfolio, and insight — confirm `REDIS_URL` is absent (will fail on unfixed code)
2. **Missing Kafka Vars Test**: Inspect planned Lambda environment for portfolio, market-data, and insight — confirm `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` are absent (will fail on unfixed code)
3. **Incorrect Profile Test**: Inspect `SPRING_PROFILES_ACTIVE` in the plan — confirm it is `"aws"` not `"prod,aws"` (will fail on unfixed code)
4. **deploy.yml Full-Replace Test**: Parse `deploy.yml` YAML and confirm the `update-function-configuration` step exists with an incomplete `Variables` map (will fail on unfixed code)

**Expected Counterexamples**:

- `terraform show -json tfplan` shows `SPRING_PROFILES_ACTIVE = "aws"` on all Lambdas
- `terraform show -json tfplan` shows no `REDIS_URL` on any Lambda
- `deploy.yml` contains `update-function-configuration` step that would wipe Terraform-managed vars
- `sync-secrets.sh` contains `--lambda` flag that bypasses Terraform

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**

```
FOR ALL input WHERE isBugCondition(input) DO
  result := terraformApply_fixed(input)
  ASSERT allRequiredEnvVarsPresent(result)
  ASSERT result.SPRING_PROFILES_ACTIVE == "prod,aws"
  ASSERT deployYml_doesNotCall("update-function-configuration")
  ASSERT syncSecrets_doesNotHave("--lambda")
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT terraformApply_original(input).imageBuildSteps == terraformApply_fixed(input).imageBuildSteps
  ASSERT terraformApply_original(input).frontendDeploySteps == terraformApply_fixed(input).frontendDeploySteps
  ASSERT terraformApply_original(input).assertPlanChecks == terraformApply_fixed(input).assertPlanChecks
  ASSERT terraformApply_original(input).ghSecretSetPath == terraformApply_fixed(input).ghSecretSetPath
  ASSERT terraformApply_original(input).existingEnvVars SUBSET OF terraformApply_fixed(input).envVars
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It generates many Terraform variable combinations to verify Lambda environment completeness
- It catches edge cases where optional variables (empty strings, nulls) interact with the merge pattern
- It provides strong guarantees that existing env vars are never dropped by the fix

**Test Plan**: Observe behavior on UNFIXED code first for existing Lambda env vars and pipeline steps, then write property-based tests capturing that behavior.

**Test Cases**:

1. **api-gateway Env Preservation**: Verify `AUTH_JWK_URI`, `CLOUDFRONT_ORIGIN_SECRET`, `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, `INSIGHT_SERVICE_URL`, `SERVER_PORT`, `PORT` remain in api-gateway Lambda environment after fix
2. **portfolio-service Env Preservation**: Verify `SPRING_DATASOURCE_URL` remains in portfolio Lambda environment after fix
3. **market-data-service Env Preservation**: Verify `SPRING_DATA_MONGODB_URI` remains in market-data Lambda environment after fix
4. **deploy.yml Image Flow Preservation**: Verify `deploy-backend` job still builds Docker image, pushes to ECR, and calls `update-function-code`
5. **deploy-frontend Job Preservation**: Verify `deploy-frontend` job is completely unchanged
6. **terraform.yml Pipeline Preservation**: Verify plan/assert/apply steps are unchanged
7. **sync-secrets.sh gh secret set Preservation**: Verify `gh secret set -f` path is unchanged
8. **assert_plan.py Checks Preservation**: Verify all existing assertion functions remain functional

### Unit Tests

- Validate Terraform HCL: `terraform validate` passes after changes
- Validate `assert_plan.py` with a mock plan JSON containing `SPRING_PROFILES_ACTIVE = "prod,aws"` on all Lambdas
- Validate `sync-secrets.sh` no longer accepts `--lambda` flag (exits with error or ignores)
- Validate `deploy.yml` YAML structure is valid after removing the config update step

### Property-Based Tests

- Generate random combinations of Terraform input variables (`redis_url`, `kafka_*`, `postgres_connection_string`, `mongodb_connection_string`) and verify all four Lambda functions include the correct env vars in their `merge()` output
- Generate random `SPRING_PROFILES_ACTIVE` values and verify `assert_plan.py` rejects any value that does not contain `"prod,aws"` (if the enhanced check is added)
- Generate random `.env.secrets` files and verify `sync-secrets.sh` only calls `gh secret set -f` (no `aws lambda` calls)

### Integration Tests

- Run `terraform plan -var-file=localstack.tfvars` with the new variables and verify the plan JSON contains all required env vars on each Lambda function
- Run `assert_plan.py` against the plan JSON and verify it passes
- Run `terraform apply -var-file=localstack.tfvars` against LocalStack and verify the Lambda functions are created with the correct environment variables
- Verify `deploy.yml` workflow syntax is valid via `actionlint` or GitHub Actions dry-run
