variable "use_localstack" {
  type        = bool
  default     = false
  description = "Toggle LocalStack vs. real AWS"
}

variable "localstack_endpoint" {
  type        = string
  default     = "http://localhost:4566"
  description = "LocalStack endpoint URL (use http://localstack:4566 when running Terraform inside Docker on the same network)"
}

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "Target AWS region"
}

variable "state_bucket_name" {
  type        = string
  description = "S3 bucket for Terraform state"
}

variable "lock_table_name" {
  type        = string
  description = "DynamoDB table for state locking"
}

variable "artifact_bucket_name" {
  type        = string
  description = "S3 bucket for Lambda JARs"
}

variable "frontend_bucket_name" {
  type        = string
  description = "S3 bucket for frontend static export"
}

variable "lambda_adapter_layer_arn" {
  type        = string
  description = "ARN of the Lambda Web Adapter layer"
}

variable "api_gateway_image_uri" {
  type        = string
  description = "Full ECR image URI for wealth-api-gateway (package_type Image). Use your account/region/repo, e.g. ...amazonaws.com/<ECR_REPOSITORY_NAME>:latest"
}

variable "lambda_java_runtime" {
  type        = string
  nullable    = true
  default     = null
  description = "Optional override for Zip Lambda runtime; default is local.lambda_defaults.zip_java_runtime in locals.tf."
}

variable "api_gateway_memory" {
  type        = number
  nullable    = true
  default     = null
  description = "Optional override for api-gateway Image memory (MB); default is local.lambda_defaults.api_gateway_memory_mb in locals.tf."
}

variable "portfolio_memory_size" {
  type        = number
  nullable    = true
  default     = null
  description = "Optional override for portfolio Lambda memory (MB); default is local.lambda_defaults.portfolio_memory_mb in locals.tf."
}

variable "market_data_memory_size" {
  type        = number
  nullable    = true
  default     = null
  description = "Optional override for market-data Lambda memory (MB); default is local.lambda_defaults.market_data_memory_mb in locals.tf."
}

variable "insight_service_memory_size" {
  type        = number
  nullable    = true
  default     = null
  description = "Optional override for insight Lambda memory (MB); default is local.lambda_defaults.insight_service_memory_mb in locals.tf."
}

variable "postgres_connection_string" {
  type        = string
  sensitive   = true
  description = "Neon/Supabase JDBC URL"
}

variable "mongodb_connection_string" {
  type        = string
  sensitive   = true
  description = "MongoDB Atlas URI"
}

variable "auth_jwk_uri" {
  type        = string
  description = "JWK endpoint URI (aws profile)"
}

variable "s3_key_api_gateway" {
  type        = string
  description = "S3 object key for api-gateway JAR"
}

variable "s3_key_portfolio" {
  type        = string
  description = "S3 object key for portfolio-service JAR"
}

variable "s3_key_market_data" {
  type        = string
  description = "S3 object key for market-data-service JAR"
}

variable "s3_key_insight" {
  type        = string
  description = "S3 object key for insight-service JAR"
}

variable "domain_name" {
  type        = string
  default     = ""
  description = "Custom domain (empty = use CloudFront default)"
}

variable "acm_certificate_arn" {
  type        = string
  default     = ""
  description = "ACM cert ARN (required when domain_name set)"
}

variable "route53_zone_id" {
  type        = string
  default     = ""
  description = "Route 53 hosted zone ID"
}

variable "cloudfront_origin_secret" {
  type        = string
  sensitive   = true
  description = "Shared secret for LURL security (CloudFront → api-gateway)"
}

# ---------------------------------------------------------------------------
# Messaging & Caching — runtime secrets owned by Terraform
# Injected via TF_VAR_* from GitHub Actions secrets in terraform.yml.
# ---------------------------------------------------------------------------

variable "redis_url" {
  type        = string
  sensitive   = true
  description = "Redis connection URL (e.g. rediss://[:password@]host:port for Upstash TLS). Used by api-gateway rate limiting, portfolio-service, and insight-service."
}

variable "kafka_bootstrap_servers" {
  type        = string
  description = "Kafka broker address (e.g. pkc-xxxxx.us-east-1.aws.confluent.cloud:9092). Used by portfolio-service, market-data-service, and insight-service."
}

variable "kafka_sasl_username" {
  type        = string
  sensitive   = true
  description = "Kafka SASL/PLAIN username for broker authentication."
}

variable "kafka_sasl_password" {
  type        = string
  sensitive   = true
  description = "Kafka SASL/PLAIN password for broker authentication."
}

# ---------------------------------------------------------------------------
# Service-to-service URL overrides (two-phase apply pattern)
# After first apply, set these via TF_VAR_* env vars or terraform.tfvars
# to wire downstream Function URLs into the api-gateway environment.
# ---------------------------------------------------------------------------

variable "portfolio_function_url" {
  type        = string
  default     = ""
  description = "portfolio-service Function URL (populated after first apply for service-to-service wiring)"
}

variable "market_data_function_url" {
  type        = string
  default     = ""
  description = "market-data-service Function URL (populated after first apply for service-to-service wiring)"
}

variable "insight_function_url" {
  type        = string
  default     = ""
  description = "insight-service Function URL (populated after first apply for service-to-service wiring)"
}

# ---------------------------------------------------------------------------
# Database module variables
# ---------------------------------------------------------------------------

variable "enable_aws_managed_database" {
  type        = bool
  default     = false
  description = "Provision AWS RDS PostgreSQL and ElastiCache (paid). Default false: use Neon/Atlas and external cache."
}

variable "lambda_vpc_subnet_ids" {
  type        = list(string)
  default     = []
  description = "Lambda subnet IDs when running inside a VPC (typically alongside managed RDS/ElastiCache). Leave empty to keep Lambda out of VPC."
}

variable "lambda_vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Lambda security group IDs when running inside a VPC (typically alongside managed RDS/ElastiCache). Leave empty to keep Lambda out of VPC."
}

variable "db_username" {
  type        = string
  description = "Master username for the RDS PostgreSQL instance"
}

variable "db_password" {
  type        = string
  sensitive   = true
  description = "Master password for the RDS PostgreSQL instance"
}

variable "rds_instance_class" {
  type        = string
  default     = "db.t3.micro"
  description = "RDS instance class (Free Tier: db.t3.micro)"
}

variable "rds_allocated_storage" {
  type        = number
  default     = 20
  description = "Allocated storage in GB for the RDS instance"
}

variable "elasticache_node_type" {
  type        = string
  default     = "cache.t3.micro"
  description = "ElastiCache node type (Free Tier: cache.t3.micro)"
}

variable "lambda_timeout" {
  type        = number
  nullable    = true
  default     = null
  description = "Optional override for Lambda timeout (seconds); default is local.lambda_defaults.lambda_timeout_seconds in locals.tf."
}
