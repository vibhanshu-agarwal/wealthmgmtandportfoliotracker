# =============================================================================
# Compute Module Variables
# Full descriptions and validation logic live in the root variables.tf.
# Sensitive flags are duplicated here so Terraform masks values in module output.
# =============================================================================

variable "artifact_bucket_name" {
  description = "S3 bucket name used for deployment artifacts (legacy; functions use Image package type)."
  type        = string
}

variable "api_gateway_image_uri" {
  description = "Full ECR image URI for the api-gateway Lambda (package_type Image)."
  type        = string
}

# ---------------------------------------------------------------------------
# Service image URIs — all three backend services use Image package type
# ---------------------------------------------------------------------------

variable "portfolio_image_uri" {
  description = "Full ECR image URI for the portfolio-service Lambda (package_type Image)."
  type        = string
}

variable "market_data_image_uri" {
  description = "Full ECR image URI for the market-data-service Lambda (package_type Image)."
  type        = string
}

variable "insight_image_uri" {
  description = "Full ECR image URI for the insight-service Lambda (package_type Image)."
  type        = string
}

variable "api_gateway_memory" {
  description = "Memory allocation in MB for the api-gateway Lambda."
  type        = number
}

variable "portfolio_memory_size" {
  description = "Memory allocation in MB for the portfolio-service Lambda."
  type        = number
}

variable "market_data_memory_size" {
  description = "Memory allocation in MB for the market-data-service Lambda."
  type        = number
}

variable "insight_service_memory_size" {
  description = "Memory allocation in MB for the insight-service Lambda."
  type        = number
}

variable "postgres_connection_string" {
  description = "JDBC connection URL for the PostgreSQL data source (Neon/Supabase)."
  type        = string
  sensitive   = true
}

variable "postgres_username" {
  description = "PostgreSQL username injected as SPRING_DATASOURCE_USERNAME."
  type        = string
  sensitive   = true
}

variable "postgres_password" {
  description = "PostgreSQL password injected as SPRING_DATASOURCE_PASSWORD."
  type        = string
  sensitive   = true
}

variable "mongodb_connection_string" {
  description = "MongoDB Atlas URI injected as SPRING_DATA_MONGODB_URI."
  type        = string
  sensitive   = true
}

variable "auth_jwk_uri" {
  description = "Deprecated for the current single-user auth path. Reserved for a future external IdP/JWK profile."
  type        = string
}

variable "auth_jwt_secret" {
  description = "HS256 JWT signing/validation secret injected into the api-gateway Lambda as AUTH_JWT_SECRET."
  type        = string
  sensitive   = true
}

variable "app_auth_email" {
  description = "Production demo login email injected into the api-gateway Lambda as APP_AUTH_EMAIL."
  type        = string
  sensitive   = true
}

variable "app_auth_password" {
  description = "Production demo login password injected into the api-gateway Lambda as APP_AUTH_PASSWORD."
  type        = string
  sensitive   = true
}

variable "app_auth_user_id" {
  description = "Production demo user ID injected into APP_AUTH_USER_ID. Must match the golden-state seeded portfolio user."
  type        = string
}

variable "app_auth_name" {
  description = "Production demo display name injected into the api-gateway Lambda as APP_AUTH_NAME."
  type        = string
}

variable "cloudfront_origin_secret" {
  description = "Shared secret injected into CloudFront and validated by the api-gateway Spring filter."
  type        = string
  sensitive   = true
}

# ---------------------------------------------------------------------------
# Messaging & Caching — runtime secrets
# ---------------------------------------------------------------------------

variable "redis_url" {
  description = "Redis connection URL (e.g. rediss://[:password@]host:port for Upstash TLS)."
  type        = string
  sensitive   = true
}

variable "kafka_bootstrap_servers" {
  description = "Kafka broker address (e.g. pkc-xxxxx.us-east-1.aws.confluent.cloud:9092)."
  type        = string
}

variable "kafka_sasl_username" {
  description = "Kafka SASL/PLAIN username for broker authentication."
  type        = string
  sensitive   = true
}

variable "kafka_sasl_password" {
  description = "Kafka SASL/PLAIN password for broker authentication."
  type        = string
  sensitive   = true
}

variable "internal_api_key" {
  description = "Shared secret gating /api/internal/** endpoints; validated per-service by InternalApiKeyFilter."
  type        = string
  sensitive   = true
}

variable "portfolio_function_url" {
  description = "Portfolio-service Function URL for service-to-service wiring (two-phase apply)."
  type        = string
}

variable "market_data_function_url" {
  description = "Market-data-service Function URL for service-to-service wiring (two-phase apply)."
  type        = string
}

variable "insight_function_url" {
  description = "Insight-service Function URL for service-to-service wiring (two-phase apply)."
  type        = string
}

variable "enable_aws_managed_database" {
  description = "When true, attaches Lambdas to the VPC for RDS/ElastiCache access."
  type        = bool
}

variable "lambda_vpc_subnet_ids" {
  description = "Subnet IDs for Lambda VPC attachment (used when enable_aws_managed_database = true)."
  type        = list(string)
}

variable "lambda_vpc_security_group_ids" {
  description = "Security group IDs for Lambda VPC attachment (used when enable_aws_managed_database = true)."
  type        = list(string)
}

variable "lambda_timeout" {
  description = "Lambda function timeout in seconds."
  type        = number
}

variable "lambda_architecture" {
  description = "Lambda instruction set architecture: arm64 (Graviton2) or x86_64."
  type        = string

  validation {
    condition     = contains(["arm64", "x86_64"], var.lambda_architecture)
    error_message = "lambda_architecture must be \"arm64\" or \"x86_64\"."
  }
}

variable "enable_provisioned_concurrency" {
  description = "When true, provisions 1 warm instance on the live alias for api-gateway and portfolio-service."
  type        = bool
}
