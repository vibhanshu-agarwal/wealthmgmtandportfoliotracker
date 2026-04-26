# Terraform Infrastructure — Wealth Management & Portfolio Tracker

Provisions the serverless AWS infrastructure: four Spring Boot microservices as Lambda functions
(Java 21 + Lambda Web Adapter), fronted by a CloudFront distribution. All resources stay within
the AWS Always Free Tier.

## Prerequisites

- Terraform >= 1.6.0
- AWS CLI configured (for real AWS deploys)
- Docker + Docker Compose (for LocalStack testing)

---

## Bootstrap Sequence (run once before first `terraform init`)

The S3 state backend and DynamoDB lock table must exist before the main root can be initialized.

```bash
# Step 1: Bootstrap the state backend
cd infrastructure/terraform/bootstrap
terraform init          # uses local state
terraform apply \
  -var="state_bucket_name=<your-bucket-name>" \
  -var="lock_table_name=<your-table-name>"

# Step 2: Initialize the main root with the S3 backend
cd ..
terraform init \
  -backend-config="bucket=<your-bucket-name>" \
  -backend-config="dynamodb_table=<your-table-name>" \
  -backend-config="region=us-east-1"

# Step 3: First apply (service-to-service URLs are empty on first apply)
terraform apply -var-file=terraform.tfvars

# Step 4: Second apply (wire downstream Function URLs into api-gateway)
# After step 3, get the Function URL outputs:
terraform output portfolio_function_url
terraform output market_data_function_url
terraform output insight_function_url
# Then re-apply with the URLs:
TF_VAR_portfolio_function_url=$(terraform output -raw portfolio_function_url) \
TF_VAR_market_data_function_url=$(terraform output -raw market_data_function_url) \
TF_VAR_insight_function_url=$(terraform output -raw insight_function_url) \
terraform apply -var-file=terraform.tfvars
```

---

## LocalStack Setup (local integration testing)

```bash
# Start LocalStack alongside existing dev services
docker compose -f docker-compose.yml -f docker-compose.localstack.yml up -d localstack

# Initialize Terraform against LocalStack (no real S3 backend needed)
cd infrastructure/terraform
terraform init -backend-config="bucket=wealth-tf-state-local" \
               -backend-config="dynamodb_table=wealth-tf-lock-local" \
               -backend-config="region=us-east-1" \
               -backend-config="endpoint=http://localhost:4566" \
               -backend-config="force_path_style=true" \
               -backend-config="skip_credentials_validation=true" \
               -backend-config="skip_metadata_api_check=true"

# Apply against LocalStack
terraform apply -var-file=localstack.tfvars -auto-approve

# Verify resources
aws --endpoint-url=http://localhost:4566 lambda list-functions
aws --endpoint-url=http://localhost:4566 s3 ls
aws --endpoint-url=http://localhost:4566 dynamodb list-tables

# Teardown
terraform destroy -var-file=localstack.tfvars -auto-approve
```

---

## Secret Injection Flow (GitHub Actions CI)

Sensitive values never appear in source-controlled files. The flow is:

```
GitHub Actions Secrets (repository settings)
  │  TF_VAR_postgres_connection_string
  │  TF_VAR_mongodb_connection_string
  │  TF_VAR_cloudfront_origin_secret
  │  TF_VAR_auth_jwt_secret
  │  TF_VAR_app_auth_email / TF_VAR_app_auth_password
  │  AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION
  ▼
Terraform process (reads TF_VAR_* automatically)
  ▼
Lambda environment variables (injected at apply time)
  ▼
Spring Boot reads env vars at startup via application-aws.yml
```

The current production demo auth path is single-user HS256 JWT auth:

- `AUTH_JWT_SECRET` signs and validates JWTs in `api-gateway`.
- `APP_AUTH_EMAIL` and `APP_AUTH_PASSWORD` are the production demo credentials.
- `APP_AUTH_USER_ID` must remain `00000000-0000-0000-0000-000000000e2e` unless the golden-state seeder is changed to seed a different user.
- `AUTH_JWK_URI` is reserved for a future external identity-provider profile and is not used by the current `prod,aws` auth path.

---

## SnapStart Alias Pattern

SnapStart snapshots are only activated on **published versions**, not `$LATEST`. This implementation:

1. Sets `publish = true` on all Lambda functions — Terraform publishes a new version on every apply.
2. Creates a `"live"` alias pointing to the published version number.
3. Attaches Function URLs to the `"live"` alias (not `$LATEST`).

This ensures SnapStart snapshots are always active on the live traffic path.

---

## LURL Security Pattern

Direct access to Lambda Function URLs is blocked by the `CloudFrontOriginVerifyFilter` in the
api-gateway Spring Boot service:

```
Browser → CloudFront → [injects X-Origin-Verify: <secret>] → api-gateway Function URL
                                                               ↓
                                                   CloudFrontOriginVerifyFilter
                                                   (validates header, strips it, forwards)
                                                               ↓
                                                   JWT authentication filter
                                                               ↓
                                                   Downstream services
```

The `cloudfront_origin_secret` flows as:

- GitHub Actions Secret → `TF_VAR_cloudfront_origin_secret`
- Terraform sensitive variable → CloudFront `custom_header { X-Origin-Verify }`
- Terraform sensitive variable → Lambda env var `CLOUDFRONT_ORIGIN_SECRET`

Any request hitting the Function URL directly (without the correct header) receives HTTP 403.

---

## Free Tier Guardrails

The following resource types are structurally absent from all `.tf` files:

- `aws_ecs_cluster`, `aws_ecs_service`, `aws_lb`, `aws_nat_gateway`
- `aws_db_instance`, `aws_rds_cluster`, `aws_docdb_cluster`
- `aws_elasticache_cluster`

Lambda concurrency is capped at `reserved_concurrent_executions = 10` per function.
CloudFront uses `price_class = "PriceClass_100"`.
