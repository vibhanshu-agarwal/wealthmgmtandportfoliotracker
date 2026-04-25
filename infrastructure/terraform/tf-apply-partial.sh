#!/usr/bin/env bash
# tf-apply-partial.sh — Apply non-Lambda resources (CloudFront, S3)
# Lambda functions require real ECR images — apply those after Phase C pushes images
# Run from repo root: bash infrastructure/terraform/tf-apply-partial.sh

set -euo pipefail

# Load secrets from .env.secrets
if [ -f .env.secrets ]; then
  while IFS='=' read -r key val; do
    [[ "$key" =~ ^[[:space:]]*# ]] && continue
    [[ -z "$key" ]] && continue
    case "$key" in
      POSTGRES_CONNECTION_STRING) export TF_VAR_postgres_connection_string="$val" ;;
      MONGODB_CONNECTION_STRING)  export TF_VAR_mongodb_connection_string="$val" ;;
      AUTH_JWK_URI)               export TF_VAR_auth_jwk_uri="$val" ;;
      CLOUDFRONT_ORIGIN_SECRET)   export TF_VAR_cloudfront_origin_secret="$val" ;;
      REDIS_URL)                  export TF_VAR_redis_url="$val" ;;
      KAFKA_BOOTSTRAP_SERVERS)    export TF_VAR_kafka_bootstrap_servers="$val" ;;
      KAFKA_SASL_USERNAME)        export TF_VAR_kafka_sasl_username="$val" ;;
      KAFKA_SASL_PASSWORD)        export TF_VAR_kafka_sasl_password="$val" ;;
      RDS_MASTER_USERNAME)        export TF_VAR_db_username="$val" ;;
      RDS_MASTER_PASSWORD)        export TF_VAR_db_password="$val" ;;
    esac
  done < .env.secrets
fi

export TF_VAR_api_gateway_image_uri="844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-api-gateway:latest"
export TF_VAR_portfolio_image_uri="844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-portfolio-service:latest"
export TF_VAR_market_data_image_uri="844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-market-data-service:latest"
export TF_VAR_insight_image_uri="844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-insight-service:latest"

VARS=(
  -var="state_bucket_name=vibhanshu-tf-state-2026"
  -var="lock_table_name=vibhanshu-terraform-locks"
  -var="artifact_bucket_name=wealth-artifacts-local"
  -var="frontend_bucket_name=vibhanshu-s3-wealthmgmt-demo-bucket"
  -var="enable_aws_managed_database=false"
)

TERRAFORM="/c/Users/SAM/bin/terraform.exe"

echo "=== Applying CloudFront distribution and S3 resources ==="
echo "    (Lambda functions skipped — need real ECR images first)"
echo ""

"$TERRAFORM" -chdir=infrastructure/terraform apply \
  "${VARS[@]}" \
  -input=false \
  -auto-approve \
  -target=module.networking.aws_cloudfront_distribution.main \
  -target=aws_s3_bucket_versioning.artifacts \
  -target=aws_s3_bucket_public_access_block.artifacts

echo ""
echo "=== Partial apply complete ==="
echo "Next steps:"
echo "  1. Complete Phase C (update deploy.yml + terraform.yml)"
echo "  2. Push images to ECR via deploy.yml"
echo "  3. Run full apply after images exist in ECR"
