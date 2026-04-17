# LocalStack integration test configuration
# Use with: terraform apply -var-file=localstack.tfvars -auto-approve
# Requires: docker compose -f docker-compose.yml -f docker-compose.localstack.yml up -d localstack

use_localstack = true
aws_region     = "us-east-1"

# State backend (LocalStack)
state_bucket_name = "wealth-tf-state-local"
lock_table_name   = "wealth-tf-lock-local"

# Artifact bucket
artifact_bucket_name = "wealth-artifacts-local"

# Frontend static bucket
frontend_bucket_name = "wealth-frontend-local"

# Lambda Web Adapter layer ARN (stub for LocalStack — layer content not validated locally)
lambda_adapter_layer_arn = "arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerX86:24"

# S3 keys (stub — JARs don't need to exist for plan/apply validation)
s3_key_api_gateway = "api-gateway/api-gateway.jar"
s3_key_portfolio   = "portfolio-service/portfolio-service.jar"
s3_key_market_data = "market-data-service/market-data-service.jar"
s3_key_insight     = "insight-service/insight-service.jar"

# JWK URI (stub for LocalStack)
auth_jwk_uri = "http://localhost:4566/jwks"

# Optional domain (disabled for LocalStack)
domain_name         = ""
acm_certificate_arn = ""
route53_zone_id     = ""

# SENSITIVE — injected via TF_VAR_* in CI; stub values for LocalStack testing only
# DO NOT commit real secrets here
postgres_connection_string = "jdbc:postgresql://localhost:5432/wealth_local"
mongodb_connection_string  = "mongodb://localhost:27017/wealth_local"
cloudfront_origin_secret   = "localstack-test-secret-do-not-use-in-production"

# Database module (LocalStack RDS + ElastiCache)
db_username           = "wealth_user"
db_password           = "wealth_pass"
rds_instance_class    = "db.t3.micro"
rds_allocated_storage = 20
elasticache_node_type = "cache.t3.micro"
