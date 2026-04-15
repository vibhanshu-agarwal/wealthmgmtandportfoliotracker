# Implementation Plan: terraform-serverless-infra

## Overview

Provision the full Terraform-based serverless infrastructure for the Wealth Management &
Portfolio Tracker. The four Spring Boot microservices are deployed as AWS Lambda functions
(Java 21 runtime + Lambda Web Adapter), fronted by a CloudFront distribution. All resources
stay within the AWS Always Free Tier. The implementation follows a strict bootstrap-first
sequence: the S3 state backend and DynamoDB lock table must exist before the main Terraform
root is initialized.

Key corrections from the design doc applied here:

- SnapStart aliases: Function URLs attach to the `"live"` alias (published version), NOT `$LATEST`
- LURL Security: CloudFront injects a secret custom header; the api-gateway Spring Security
  filter rejects requests that do not carry it, preventing direct Function URL access

---

## Tasks

- [x] 1. Create the bootstrap configuration (S3 state backend + DynamoDB lock table)
  - Create `infrastructure/terraform/bootstrap/` directory with `main.tf`, `variables.tf`,
    and `outputs.tf`
  - `main.tf` defines `aws_s3_bucket` (state bucket) with versioning enabled and
    `aws_dynamodb_table` (lock table) with `LockID` partition key and `PAY_PER_REQUEST` billing
  - `variables.tf` declares `state_bucket_name`, `lock_table_name`, and `aws_region`
  - `outputs.tf` exposes `state_bucket_name` and `lock_table_name`
  - Bootstrap uses a **local** state file — it must NOT reference the S3 backend it is creating
  - No `aws_nat_gateway`, `aws_vpc`, or any paid resource type may appear in bootstrap
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 2. Scaffold the root Terraform module skeleton
  - [x] 2.1 Create `infrastructure/terraform/versions.tf` - Declare `required_version = ">= 1.6.0"` and `required_providers { aws = { source =
"hashicorp/aws", version = ">= 5.0" } }` - _Requirements: 16.3_
  - [x] 2.2 Create `infrastructure/terraform/variables.tf`
    - Declare all root input variables from the design's variable contract table, including
      `use_localstack` (bool, default `false`), `aws_region` (string, default `"us-east-1"`),
      `state_bucket_name`, `lock_table_name`, `artifact_bucket_name`,
      `lambda_adapter_layer_arn`, `postgres_connection_string` (sensitive),
      `mongodb_connection_string` (sensitive), `auth_jwk_uri`, `s3_key_api_gateway`,
      `s3_key_portfolio`, `s3_key_market_data`, `s3_key_insight`, `domain_name` (default `""`),
      `acm_certificate_arn` (default `""`), `route53_zone_id` (default `""`),
      and `cloudfront_origin_secret` (sensitive) — the shared secret for LURL security
    - _Requirements: 2.1, 2.4, 7.1, 7.2_
  - [x] 2.3 Create `infrastructure/terraform/main.tf` (provider block + S3 backend + artifact bucket)
    - Implement the `provider "aws"` block with the `dynamic "endpoints"` pattern for the
      LocalStack toggle (see design doc provider toggle section)
    - Declare the `terraform { backend "s3" { ... } }` block referencing `state_bucket_name`
      and `lock_table_name` variables
    - Define `aws_s3_bucket` for Lambda deployment artifacts with versioning enabled and
      `aws_s3_bucket_public_access_block` blocking all public access
    - Module instantiation stubs for `module "compute"` and `module "networking"` (fill in
      after modules are created)
    - _Requirements: 1.2, 2.2, 2.3, 3.1, 11.1, 11.2, 11.4_
  - [x] 2.4 Create `infrastructure/terraform/outputs.tf`
    - Expose `cloudfront_domain_name` (from networking module output) as the public endpoint URL
    - _Requirements: 1.3, 9.6_
  - [x] 2.5 Create `infrastructure/terraform/terraform.tfvars.example`
    - Document every required variable with a descriptive placeholder value
    - Include a comment block explaining the bootstrap sequence and secret injection flow
    - _Requirements: 1.4_

- [x] 3. Verify the provider toggle compiles and remaps correctly
  - Run `terraform init` then `terraform validate` in `infrastructure/terraform/` to confirm
    zero HCL errors
  - Run `terraform plan -var="use_localstack=true"` (with stub values for required variables)
    and confirm the plan output shows the provider configured with LocalStack endpoints
    (`http://localhost:4566`) and no real AWS endpoints
  - Run `terraform plan -var="use_localstack=false"` and confirm no custom endpoint overrides
    appear in the provider configuration
  - This task gates all subsequent resource work — do not proceed until both plan outputs are
    verified
  - _Requirements: 2.1, 2.2, 2.3_

- [x] 4. Implement the compute module — IAM roles and Lambda functions
  - [x] 4.1 Create `infrastructure/terraform/modules/compute/variables.tf`
    - Declare all compute module inputs: `artifact_bucket_name`, `s3_key_*` (×4),
      `lambda_adapter_layer_arn`, `postgres_connection_string` (sensitive),
      `mongodb_connection_string` (sensitive), `auth_jwk_uri`,
      `portfolio_function_url`, `market_data_function_url`, `insight_function_url`
      (service-to-service URLs, populated after first apply), and
      `cloudfront_origin_secret` (sensitive)
    - _Requirements: 4.1, 5.1, 6.1, 7.3_
  - [x] 4.2 Create IAM execution roles for all four Lambda functions
    - In `modules/compute/main.tf`, define one `aws_iam_role` per function with the Lambda
      assume-role trust policy
    - Attach `arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole` to each role
      via `aws_iam_role_policy_attachment`
    - Add an inline policy to the insight-service role granting `bedrock:InvokeModel` on the
      required model ARN only (no wildcard resource)
    - Do NOT attach `AdministratorAccess` or any `"Resource": "*"` policy to any role
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
  - [x] 4.3 Implement the four `aws_lambda_function` resources
    - Runtime: `java21`; `publish = true`; `snap_start { apply_on = "PublishedVersions" }`
    - `s3_bucket` and `s3_key` from input variables; `layers = [var.lambda_adapter_layer_arn]`
    - `memory_size = 512`; `timeout = 30`; `reserved_concurrent_executions = 10`
    - `JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"`
    - `SPRING_PROFILES_ACTIVE = "aws"` on every function
    - api-gateway additionally receives: `PORTFOLIO_SERVICE_URL`, `MARKET_DATA_SERVICE_URL`,
      `INSIGHT_SERVICE_URL`, `AUTH_JWK_URI`, and `CLOUDFRONT_ORIGIN_SECRET`
      (the shared secret used by the Spring Security filter)
    - portfolio-service additionally receives: `SPRING_DATASOURCE_URL`
    - market-data-service additionally receives: `SPRING_DATA_MONGODB_URI`
    - insight-service additionally receives any declared extra env vars
    - Do NOT add `vpc_config` to any function
    - _Requirements: 4.1–4.7, 5.1–5.6, 6.1–6.4, 8.1, 8.2, 13.1–13.4, 15.2_
  - [x] 4.4 Create `aws_lambda_alias "live"` for each function
    - Each alias must point to `aws_lambda_function.{name}.version` (the published version
      number, not `$LATEST`)
    - SnapStart snapshots are only activated on published versions — `$LATEST` does NOT benefit
      from SnapStart; the alias is mandatory
    - Name the alias `"live"` for all four functions
    - _Requirements: 13.4_
  - [x] 4.5 Create `aws_lambda_function_url` resources attached to the `"live"` alias
    - Set `qualifier = aws_lambda_alias.{name}_live.name` on each Function URL resource
    - `authorization_type = "NONE"` on all four (auth delegated to Spring Security / CloudFront
      header check)
    - The api-gateway Function URL is the CloudFront origin; downstream Function URLs are
      consumed only by the api-gateway Lambda
    - _Requirements: 4.5, 5.5, 6.4_
  - [x] 4.6 Create `infrastructure/terraform/modules/compute/outputs.tf`
    - Expose `api_gateway_function_url`, `portfolio_function_url`, `market_data_function_url`,
      `insight_function_url` — each sourced from the corresponding `aws_lambda_function_url`
      resource
    - _Requirements: 1.1_

- [x] 5. Checkpoint — validate compute module
  - Run `terraform validate` from `infrastructure/terraform/` — zero errors required
  - Run `terraform plan -var="use_localstack=true"` with stub variable values and confirm all
    four Lambda functions, four IAM roles, four aliases, and four Function URLs appear in the
    plan with `actions = ["create"]`
  - Confirm `reserved_concurrent_executions = 10` for every Lambda resource in the plan JSON
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement the networking module
  - [x] 6.1 Create `infrastructure/terraform/modules/networking/variables.tf`
    - Declare: `origin_url` (api-gateway Function URL), `cloudfront_origin_secret` (sensitive,
      the header value CloudFront injects), `domain_name` (default `""`),
      `acm_certificate_arn` (default `""`), `route53_zone_id` (default `""`)
    - _Requirements: 9.1, 9.4, 9.5_
  - [x] 6.2 Implement `aws_cloudfront_distribution` in `modules/networking/main.tf`
    - Origin: `domain_name` extracted from `var.origin_url` (strip `https://` prefix)
    - Add a `custom_header` block to the origin configuration injecting
      `X-Origin-Verify = var.cloudfront_origin_secret` — this is the LURL security header
      that CloudFront sends on every request to the api-gateway Function URL
    - `viewer_protocol_policy = "redirect-to-https"`
    - Forward all HTTP methods: `["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]`
    - `price_class = "PriceClass_100"`
    - HTTPS-only viewer protocol; no WAF, no logging (free tier)
    - When `var.domain_name != ""`: add `aliases = [var.domain_name]` and associate
      `var.acm_certificate_arn` via `viewer_certificate` block
    - When `var.domain_name == ""`: use `viewer_certificate { cloudfront_default_certificate = true }`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 15.3_
  - [x] 6.3 Implement optional `aws_route53_record` (A alias)
    - Use `count = var.domain_name != "" ? 1 : 0` to conditionally create the record
    - Type `A`, alias target pointing to the CloudFront distribution domain and hosted zone ID
    - _Requirements: 10.1, 10.2, 10.3_
  - [x] 6.4 Create `infrastructure/terraform/modules/networking/outputs.tf`
    - Expose `cloudfront_domain_name`
    - _Requirements: 9.6_

- [x] 7. Implement the LURL security filter in api-gateway
  - [x] 7.1 Create `CloudFrontOriginVerifyFilter.java` in `api-gateway/src/main/java/com/wealth/gateway/`
    - Implement a Spring WebFlux `WebFilter` (or `GlobalFilter` for Spring Cloud Gateway) that
      reads the `X-Origin-Verify` request header
    - Compare the header value against the value of the `CLOUDFRONT_ORIGIN_SECRET` environment
      variable (injected by Terraform)
    - If the header is absent or the value does not match: return HTTP 403 Forbidden immediately,
      do not forward the request downstream
    - If the header matches: strip the header from the forwarded request (do not leak the secret
      to downstream services) and continue the filter chain
    - The filter must run before JWT authentication so unauthenticated probes are rejected cheaply
    - _Requirements: 9.1, 9.3_ (LURL security directive)
  - [ ]\* 7.2 Write unit tests for `CloudFrontOriginVerifyFilter`
    - Test: request with correct secret header → passes through (filter chain continues)
    - Test: request with wrong secret value → 403 returned, chain not invoked
    - Test: request with missing header → 403 returned, chain not invoked
    - Test: secret header is stripped from the forwarded request
    - _Requirements: 9.1, 9.3_

- [x] 8. Wire root module — connect compute and networking modules
  - Update `infrastructure/terraform/main.tf` to pass compute module outputs into the
    networking module: `origin_url = module.compute.api_gateway_function_url` and
    `cloudfront_origin_secret = var.cloudfront_origin_secret`
  - Pass `module.compute.portfolio_function_url`, `module.compute.market_data_function_url`,
    and `module.compute.insight_function_url` back into the compute module as
    `portfolio_function_url`, `market_data_function_url`, `insight_function_url` inputs
    (service-to-service URL wiring)
  - Pass `cloudfront_origin_secret` from root variable into both compute module (for Lambda
    env var injection) and networking module (for CloudFront custom header)
  - Update `outputs.tf` to source `cloudfront_domain_name` from `module.networking`
  - _Requirements: 1.2, 1.3, 9.1_

- [x] 9. Create LocalStack integration test configuration
  - [x] 9.1 Create `infrastructure/terraform/localstack.tfvars`
    - Set `use_localstack = true`, `aws_region = "us-east-1"`
    - Provide stub (non-empty) values for all sensitive variables:
      `postgres_connection_string`, `mongodb_connection_string`, `cloudfront_origin_secret`
    - Provide stub S3 keys and bucket names
    - _Requirements: 14.1_
  - [x] 9.2 Create `docker-compose.localstack.yml` in the repo root
    - Define a `localstack` service using `localstack/localstack:latest`
    - Expose port `4566:4566`
    - Set `SERVICES: lambda,s3,dynamodb,cloudfront,iam,acm,route53`
    - _Requirements: 14.3_
  - [x] 9.3 Create `infrastructure/terraform/README.md` - Document the full bootstrap sequence (5-step process from design doc) - Document LocalStack setup: `docker compose -f docker-compose.yml -f
docker-compose.localstack.yml up -d localstack` then `terraform apply
-var-file=localstack.tfvars` - Document the GitHub Actions secret → `TF_VAR_*` → Lambda env var injection flow - Document the SnapStart alias pattern and why Function URLs attach to `"live"` not `$LATEST` - Document the LURL security pattern (CloudFront header → Spring filter) - _Requirements: 14.3_

- [x] 10. Checkpoint — LocalStack end-to-end validation
  - Start LocalStack: `docker compose -f docker-compose.yml -f docker-compose.localstack.yml
up -d localstack`
  - Run `terraform init` then `terraform apply -var-file=localstack.tfvars -auto-approve`
    from `infrastructure/terraform/`
  - Verify Lambda functions created: `aws --endpoint-url=http://localhost:4566 lambda list-functions`
  - Verify S3 buckets created: `aws --endpoint-url=http://localhost:4566 s3 ls`
  - Verify DynamoDB table (from bootstrap): `aws --endpoint-url=http://localhost:4566 dynamodb list-tables`
  - Run `terraform destroy -var-file=localstack.tfvars -auto-approve` and confirm clean teardown
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Create the GitHub Actions CI workflow
  - Create `.github/workflows/terraform.yml`
  - Job 1 (`validate`, runs on every PR): `terraform init` → `terraform validate` →
    `terraform plan -out=tfplan` → `terraform show -json tfplan > plan.json`
  - Job 2 (`apply`, runs on push to `main` only): `terraform apply tfplan`
  - Inject secrets via `env:` block: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
    `AWS_REGION`, `TF_VAR_postgres_connection_string`, `TF_VAR_mongodb_connection_string`,
    `TF_VAR_auth_jwk_uri`, `TF_VAR_cloudfront_origin_secret`
  - Set `working-directory: infrastructure/terraform` on all Terraform steps
  - _Requirements: 16.1, 16.2, 16.4_

- [x] 12. Add plan assertion script for CI property validation
  - Create `infrastructure/terraform/scripts/assert_plan.py` (Python, no external dependencies)
  - Parse `plan.json` (output of `terraform show -json tfplan`) and assert:
    - No prohibited resource types in `resource_changes` (Property 4: `aws_ecs_cluster`,
      `aws_ecs_service`, `aws_lb`, `aws_nat_gateway`, `aws_db_instance`, `aws_rds_cluster`,
      `aws_docdb_cluster`, `aws_elasticache_cluster`) — **Validates: Requirements 8.1, 15.1**
    - All four Lambda functions appear with `actions` containing `"create"` or `"update"` —
      **Validates: Requirements 4.1, 5.1, 6.1**
    - `reserved_concurrent_executions <= 10` for every Lambda resource —
      **Validates: Requirement 15.2 / Property 7**
    - CloudFront `price_class == "PriceClass_100"` — **Validates: Requirement 15.3**
    - `SPRING_PROFILES_ACTIVE` env var present on all four Lambda resources —
      **Validates: Requirements 4.7, 5.6, 6.2, 6.3 / Property 3**
    - Route 53 record present if and only if `domain_name` is non-empty —
      **Validates: Requirements 10.1, 10.3 / Property 6**
  - Add a step to Job 1 in `terraform.yml` that runs `python3 scripts/assert_plan.py plan.json`
  - _Requirements: 15.1, 15.2, 15.3_

- [ ]\* 12.1 Write unit tests for `assert_plan.py`
  - Test with a synthetic plan JSON containing a prohibited resource type → script exits non-zero
  - Test with a valid plan JSON → script exits zero
  - Test `reserved_concurrent_executions > 10` → script exits non-zero
  - _Requirements: 15.1, 15.2_

- [x] 13. Commit `.terraform.lock.hcl` and finalize free-tier guardrail audit
  - Run `terraform init` to generate `.terraform.lock.hcl` in `infrastructure/terraform/`
  - Commit `.terraform.lock.hcl` to version control (do NOT add it to `.gitignore`)
  - Audit all `.tf` files across `bootstrap/`, `modules/compute/`, and `modules/networking/`
    to confirm none of the prohibited resource types appear anywhere in the codebase
  - Confirm `reserved_concurrent_executions = 10` is set on all four Lambda functions
  - Confirm `price_class = "PriceClass_100"` is set on the CloudFront distribution
  - _Requirements: 15.1, 15.2, 15.3, 15.4, 16.2_

- [x] 14. Final checkpoint — full validate and plan clean run
  - Run `terraform validate` from `infrastructure/terraform/` — must produce zero errors and
    zero warnings
  - Run `terraform plan -var="use_localstack=false" -var-file=terraform.tfvars.example` (with
    example values) and confirm the plan contains only free-tier eligible resource types
  - Run `python3 scripts/assert_plan.py plan.json` and confirm all assertions pass
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- **Bootstrap must be applied before `terraform init` in the main root** — this is a hard
  dependency, not optional
- **SnapStart correction**: Function URLs are attached to the `"live"` alias (published version),
  not `$LATEST`. SnapStart snapshots only activate on published versions.
- **LURL security**: The `cloudfront_origin_secret` flows as a GitHub Actions Secret →
  `TF_VAR_cloudfront_origin_secret` → Terraform sensitive variable → CloudFront custom header
  (`X-Origin-Verify`) AND Lambda env var (`CLOUDFRONT_ORIGIN_SECRET`). The Spring Security
  filter in api-gateway rejects any request missing the correct header value.
- **No VPC, no NAT Gateway** — Lambda functions connect to external services (Neon, MongoDB
  Atlas, Bedrock) directly over the public internet via injected connection strings
- **Sensitive variables** (`postgres_connection_string`, `mongodb_connection_string`,
  `cloudfront_origin_secret`) must never appear in `.tfvars` files committed to version control;
  they are injected exclusively via `TF_VAR_*` environment variables in CI
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at natural break points
