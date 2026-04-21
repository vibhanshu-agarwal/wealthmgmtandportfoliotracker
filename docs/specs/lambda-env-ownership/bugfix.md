# Bugfix Requirements Document

## Introduction

Commit `2d06599` rewrote `deploy.yml`'s Lambda environment block from a `cat <<EOF` heredoc to a `jq -n` builder and silently dropped `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`, and `SPRING_DATASOURCE_*` from the Lambda environment variables map. Because `aws lambda update-function-configuration --environment` performs a **full replace** of the entire `Variables` map, every `deploy.yml` run wipes those variables from the live Lambda functions.

This causes three cascading failures:

- **api-gateway / portfolio-service / insight-service**: `REDIS_URL` missing → Spring Boot falls back to `localhost:6379` → TCP connect hangs ~20s → exceeds LWA 9.8s async-init window → `Extension.Crash`
- **portfolio-service / market-data-service / insight-service**: Kafka credentials missing → services cannot connect to the Kafka broker
- **portfolio-service**: Datasource credentials missing → cannot connect to PostgreSQL

The structural root cause is **dual ownership**: both `deploy.yml` and Terraform manage Lambda environment variables, but `deploy.yml`'s full-replace semantics silently override Terraform's state. Additionally, `scripts/sync-secrets.sh --lambda` directly calls `aws lambda update-function-configuration`, creating a third source of drift outside Terraform state.

A secondary issue compounds the problem: Terraform's `common_env` sets `SPRING_PROFILES_ACTIVE` to `"aws"` instead of `"prod,aws"`. Without the `prod` profile, `application-prod.yml` never loads, so environment variable references for datasource URLs, Kafka config, and Redis config are never read by Spring Boot.

The fix is to consolidate Lambda environment variable ownership entirely into Terraform, make `deploy.yml` image-only (no env updates), remove the `sync-secrets.sh --lambda` foot-gun, and correct the Spring profile value.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `deploy.yml` runs and the `jq -n` builder constructs the Lambda environment JSON THEN the system omits `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`, and `SPRING_DATASOURCE_*` from the `Variables` map, and `aws lambda update-function-configuration --environment` performs a full replace that wipes those variables from the live Lambda

1.2 WHEN the api-gateway, portfolio-service, or insight-service Lambda starts without `REDIS_URL` in its environment THEN the system falls back to `localhost:6379`, hangs on a TCP connect timeout for ~20 seconds, exceeds the LWA 9.8s async-init window, and crashes with `Extension.Crash`

1.3 WHEN portfolio-service or market-data-service Lambda starts without `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, or `KAFKA_SASL_PASSWORD` in its environment THEN the system cannot connect to the Kafka broker and event streaming fails

1.4 WHEN portfolio-service Lambda starts without `SPRING_DATASOURCE_URL` in its environment THEN the system cannot connect to PostgreSQL and all portfolio operations fail

1.5 WHEN Terraform's `common_env` sets `SPRING_PROFILES_ACTIVE` to `"aws"` (without `"prod"`) THEN the system does not load `application-prod.yml`, so environment variable references for datasource URLs, Kafka config, and Redis config are never resolved by Spring Boot

1.6 WHEN `scripts/sync-secrets.sh --lambda` is invoked THEN the system calls `aws lambda update-function-configuration` directly, bypassing Terraform state and creating environment variable drift that Terraform cannot detect or reconcile

1.7 WHEN `deploy.yml` triggers only on the `architecture/cloud-native-extraction` branch THEN the system does not deploy on pushes to `main`, causing the two branches to diverge in deployed state

### Expected Behavior (Correct)

2.1 WHEN a Lambda function's environment variables are updated THEN the system SHALL manage the complete `Variables` map exclusively through Terraform, ensuring `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD`, and all service-specific variables are present and no variables are silently dropped

2.2 WHEN the api-gateway, portfolio-service, or insight-service Lambda starts THEN the system SHALL have `REDIS_URL` present in the Lambda environment (injected by Terraform from GitHub Secrets via `TF_VAR_redis_url`), allowing Spring Boot to connect to the configured Redis instance without falling back to localhost

2.3 WHEN portfolio-service, market-data-service, or insight-service Lambda starts THEN the system SHALL have `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SASL_USERNAME`, and `KAFKA_SASL_PASSWORD` present in the Lambda environment (injected by Terraform from GitHub Secrets), allowing Spring Kafka to connect to the broker

2.4 WHEN portfolio-service Lambda starts THEN the system SHALL have `SPRING_DATASOURCE_URL` present in the Lambda environment (already managed by Terraform via `var.postgres_connection_string`), preserving PostgreSQL connectivity

2.5 WHEN Terraform applies the Lambda environment THEN the system SHALL set `SPRING_PROFILES_ACTIVE` to `"prod,aws"` so that both `application-prod.yml` and `application-aws.yml` are loaded by Spring Boot, ensuring all environment variable references are resolved

2.6 WHEN `deploy.yml` runs THEN the system SHALL only update the Lambda function image (`update-function-code`) and SHALL NOT call `update-function-configuration` to modify environment variables, eliminating the full-replace wipe vector

2.7 WHEN `scripts/sync-secrets.sh` is invoked THEN the system SHALL only sync secrets to GitHub Actions (via `gh secret set`) and SHALL NOT have a `--lambda` option that directly modifies Lambda configuration outside Terraform state

2.8 WHEN code is pushed to either `main` or `architecture/cloud-native-extraction` THEN the system SHALL trigger `deploy.yml` for both branches, preventing deployed state divergence

### Unchanged Behavior (Regression Prevention)

3.1 WHEN `deploy.yml` runs THEN the system SHALL CONTINUE TO build the api-gateway Docker image, push it to ECR, wait for `LastUpdateStatus` to be `Successful`, and update the Lambda function code via `update-function-code`

3.2 WHEN `terraform.yml` runs on a push to `main` THEN the system SHALL CONTINUE TO run `terraform plan`, execute `assert_plan.py` correctness checks, and apply infrastructure changes including Lambda environment variables

3.3 WHEN `assert_plan.py` validates a Terraform plan THEN the system SHALL CONTINUE TO verify that `SPRING_PROFILES_ACTIVE` is present on all Lambda functions, that all four required Lambda functions exist, that `reserved_concurrent_executions` is within bounds, and that no prohibited resource types are created

3.4 WHEN the api-gateway Lambda starts THEN the system SHALL CONTINUE TO have `AUTH_JWK_URI`, `CLOUDFRONT_ORIGIN_SECRET`, `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`, and `INSIGHT_SERVICE_URL` present in its environment, preserving JWT validation, CloudFront origin security, and service-to-service routing

3.5 WHEN the portfolio-service Lambda starts THEN the system SHALL CONTINUE TO have `SPRING_DATASOURCE_URL` in its environment, preserving PostgreSQL connectivity

3.6 WHEN the market-data-service Lambda starts THEN the system SHALL CONTINUE TO have `SPRING_DATA_MONGODB_URI` in its environment, preserving MongoDB connectivity

3.7 WHEN `scripts/sync-secrets.sh` is invoked with an `.env.secrets` file THEN the system SHALL CONTINUE TO sync all key-value pairs to GitHub Actions secrets via `gh secret set -f`

3.8 WHEN Terraform applies and the api-gateway Lambda uses `package_type = "Image"` THEN the system SHALL CONTINUE TO exclude `AWS_LAMBDA_EXEC_WRAPPER` from the api-gateway environment (the image ENTRYPOINT handles the Lambda Web Adapter) while retaining it for Zip-based Lambdas via `common_env`

3.9 WHEN the `deploy-frontend` job in `deploy.yml` runs THEN the system SHALL CONTINUE TO build the Next.js static export, sync to S3, and invalidate CloudFront
