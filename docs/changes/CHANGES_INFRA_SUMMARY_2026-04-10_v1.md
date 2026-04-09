# Infrastructure Changes — Terraform Serverless Stack

**Date:** 2026-04-10  
**Scope:** Full replacement of AWS CDK v2 (TypeScript) with a Terraform-based serverless infrastructure

---

## Overview

Provisioned the complete Terraform-based serverless infrastructure for the Wealth Management & Portfolio Tracker. The four Spring Boot microservices (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) are now deployable as AWS Lambda functions (Java 21 runtime + Lambda Web Adapter), fronted by a CloudFront distribution. All resources stay within the AWS Always Free Tier.

---

## New Files

### Bootstrap (`infrastructure/terraform/bootstrap/`)

- `main.tf` — S3 state bucket (versioning enabled, public access blocked) + DynamoDB lock table (`PAY_PER_REQUEST`, `LockID` hash key). Uses local state — does NOT reference the S3 backend it creates.
- `variables.tf` — `state_bucket_name`, `lock_table_name`, `aws_region`
- `outputs.tf` — exposes `state_bucket_name` and `lock_table_name`
- `versions.tf` — Terraform >= 1.6.0, AWS provider >= 5.0

### Root Module (`infrastructure/terraform/`)

- `versions.tf` — provider version constraints
- `variables.tf` — 20 input variables; `postgres_connection_string`, `mongodb_connection_string`, `cloudfront_origin_secret` declared `sensitive = true`
- `main.tf` — LocalStack provider toggle (`dynamic "endpoints"` pattern), minimal S3 backend block, artifact S3 bucket, `module "compute"` and `module "networking"` wiring
- `outputs.tf` — `cloudfront_domain_name` output
- `terraform.tfvars.example` — documented placeholder values with bootstrap sequence and secret injection instructions
- `localstack.tfvars` — stub values for LocalStack integration testing
- `.terraform.lock.hcl` — AWS provider version pinned, committed to VCS
- `README.md` — bootstrap sequence, LocalStack setup, GitHub Actions secret flow, SnapStart alias pattern, LURL security pattern, free-tier guardrails

### Compute Module (`infrastructure/terraform/modules/compute/`)

- `variables.tf` — all compute inputs including sensitive connection strings and service-to-service URL overrides
- `main.tf`:
  - 4 `aws_iam_role` resources with Lambda assume-role trust policy
  - 4 `aws_iam_role_policy_attachment` attaching `AWSLambdaBasicExecutionRole`
  - 1 inline `aws_iam_role_policy` on insight-service granting `bedrock:InvokeModel` on a specific model ARN (no wildcard)
  - 4 `aws_lambda_function` resources: `java21` runtime, `publish = true`, `snap_start { apply_on = "PublishedVersions" }`, `memory_size = 512`, `timeout = 30`, `reserved_concurrent_executions = 10`
  - 4 `aws_lambda_alias` named `"live"` pointing to the published version (not `$LATEST`) — required for SnapStart
  - 4 `aws_lambda_function_url` attached to the `"live"` alias, `authorization_type = "NONE"`
- `outputs.tf` — `api_gateway_function_url`, `portfolio_function_url`, `market_data_function_url`, `insight_function_url`

### Networking Module (`infrastructure/terraform/modules/networking/`)

- `variables.tf` — `origin_url`, `cloudfront_origin_secret` (sensitive), `domain_name`, `acm_certificate_arn`, `route53_zone_id`
- `main.tf`:
  - `aws_cloudfront_distribution` with `PriceClass_100`, `redirect-to-https`, all HTTP methods forwarded, `custom_header { name = "X-Origin-Verify", value = var.cloudfront_origin_secret }` (LURL security)
  - Conditional `aliases` + ACM cert when `domain_name != ""`
  - Conditional `aws_route53_record` (A alias) via `count = var.domain_name != "" ? 1 : 0`
- `outputs.tf` — `cloudfront_domain_name`

### CI/CD

- `.github/workflows/terraform.yml` — two jobs: `validate` (every PR: init → validate → plan → show JSON → assert_plan.py) and `apply` (push to `main` only). All secrets injected via `env:` block.
- `infrastructure/terraform/scripts/assert_plan.py` — stdlib-only Python script asserting six correctness properties against `terraform show -json` plan output (no prohibited resource types, all 4 Lambdas present, `reserved_concurrent_executions <= 10`, `SPRING_PROFILES_ACTIVE` on all Lambdas, `PriceClass_100` on CloudFront, Route 53 record type A)

### LocalStack

- `docker-compose.localstack.yml` (repo root) — LocalStack overlay service exposing port 4566 with `lambda,s3,dynamodb,cloudfront,iam,acm,route53`

### api-gateway (Spring Boot)

- `CloudFrontOriginVerifyFilter.java` — Spring Cloud Gateway `GlobalFilter` at `Ordered.HIGHEST_PRECEDENCE`. Validates `X-Origin-Verify` header against `CLOUDFRONT_ORIGIN_SECRET` env var; returns HTTP 403 if absent or wrong; strips the header before forwarding (prevents leakage to downstream services). No-op when secret is not configured (local dev).
- `JwtAuthenticationFilter.java` — order bumped from `HIGHEST_PRECEDENCE + 1` to `HIGHEST_PRECEDENCE + 2` to preserve correct filter chain ordering.

---

## Key Design Decisions

**SnapStart alias pattern** — Function URLs attach to the `"live"` alias (published version), not `$LATEST`. SnapStart snapshots only activate on published versions; `$LATEST` does not benefit.

**LURL security** — `cloudfront_origin_secret` flows: GitHub Actions Secret → `TF_VAR_cloudfront_origin_secret` → Terraform sensitive variable → CloudFront `custom_header { X-Origin-Verify }` AND Lambda env var `CLOUDFRONT_ORIGIN_SECRET`. The `CloudFrontOriginVerifyFilter` rejects any direct Function URL access that bypasses CloudFront.

**Two-phase apply pattern** — On first apply, service-to-service URLs (`PORTFOLIO_SERVICE_URL`, etc.) are empty strings. After the first apply, the actual Function URL outputs are fed back in via `TF_VAR_*` env vars for a second apply.

**No VPC, no NAT Gateway** — Lambda functions connect to Neon/Supabase (PostgreSQL), MongoDB Atlas, and Amazon Bedrock directly over the public internet via injected connection strings.

**Free-tier guardrails** — `reserved_concurrent_executions = 10` on all Lambdas; `PriceClass_100` on CloudFront; zero prohibited resource types (`aws_ecs_*`, `aws_lb`, `aws_nat_gateway`, `aws_db_instance`, `aws_rds_*`, `aws_elasticache_*`) in any `.tf` file.
