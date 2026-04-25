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

# Lambda Web Adapter layer ARN (no longer used — all Lambdas are Image-based)
lambda_adapter_layer_arn = "arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerX86:24"

# api-gateway container image (stub URI for plan; LocalStack Lambda image support varies)
api_gateway_image_uri = "000000000000.dkr.ecr.us-east-1.amazonaws.com/wealth-api-gateway:latest"

# Service container images (stub URIs for LocalStack plan/apply validation)
portfolio_image_uri   = "000000000000.dkr.ecr.us-east-1.amazonaws.com/wealth-portfolio-service:latest"
market_data_image_uri = "000000000000.dkr.ecr.us-east-1.amazonaws.com/wealth-market-data-service:latest"
insight_image_uri     = "000000000000.dkr.ecr.us-east-1.amazonaws.com/wealth-insight-service:latest"

# JWK URI (stub for LocalStack)
auth_jwk_uri = "http://localhost:4566/jwks"

# Optional domain (disabled for LocalStack)
domain_name         = ""
acm_certificate_arn = ""
route53_zone_id     = ""

# SENSITIVE — injected via TF_VAR_* in CI; stub values for LocalStack testing only
# DO NOT commit real secrets here
postgres_connection_string = "jdbc:postgresql://localhost:5432/wealth_local"
postgres_username          = "wealth_user"
postgres_password          = "wealth_pass"
mongodb_connection_string  = "mongodb://localhost:27017/wealth_local"
cloudfront_origin_secret   = "localstack-test-secret-do-not-use-in-production"
# Messaging & Caching stubs for LocalStack testing
redis_url               = "redis://localhost:6379"
kafka_bootstrap_servers = "localhost:9092"
kafka_sasl_username     = "local-kafka-user"
kafka_sasl_password     = "local-kafka-pass"

# Database module (LocalStack RDS + ElastiCache)
db_username           = "wealth_user"
db_password           = "wealth_pass"
rds_instance_class    = "db.t3.micro"
rds_allocated_storage = 20
elasticache_node_type = "cache.t3.micro"
