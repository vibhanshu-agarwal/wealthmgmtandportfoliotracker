# =============================================================================
# Compute Module Variables
# =============================================================================

variable "artifact_bucket_name" {
  type        = string
  description = "S3 bucket for Lambda artifacts (retained for future use; no longer used for Zip Lambdas)."
}

variable "s3_key_api_gateway" {
  type        = string
  description = "S3 object key for api-gateway JAR (unused — api-gateway uses package_type Image)"
}

variable "api_gateway_image_uri" {
  type        = string
  description = "Full ECR image URI for api-gateway Lambda (package_type Image), e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo:latest"
}

# ---------------------------------------------------------------------------
# Service image URIs — all three backend services use Image package type
# ---------------------------------------------------------------------------

variable "portfolio_image_uri" {
  type        = string
  description = "Full ECR image URI for wealth-portfolio-service Lambda (package_type Image)."
}

variable "market_data_image_uri" {
  type        = string
  description = "Full ECR image URI for wealth-market-data-service Lambda (package_type Image)."
}

variable "insight_image_uri" {
  type        = string
  description = "Full ECR image URI for wealth-insight-service Lambda (package_type Image)."
}

variable "api_gateway_memory" {
  type        = number
  description = "Memory (MB) for wealth-api-gateway Image Lambda (root defaults from locals.tf)."
}

variable "portfolio_memory_size" {
  type        = number
  description = "Memory (MB) for wealth-portfolio-service Lambda."
}

variable "market_data_memory_size" {
  type        = number
  description = "Memory (MB) for wealth-market-data-service Lambda."
}

variable "insight_service_memory_size" {
  type        = number
  description = "Memory (MB) for wealth-insight-service Lambda."
}

variable "postgres_connection_string" {
  type      = string
  sensitive = true
}

variable "postgres_username" {
  type        = string
  sensitive   = true
  description = "PostgreSQL username (Neon: neondb_owner). Injected as SPRING_DATASOURCE_USERNAME."
}

variable "postgres_password" {
  type        = string
  sensitive   = true
  description = "PostgreSQL password. Injected as SPRING_DATASOURCE_PASSWORD."
}

variable "mongodb_connection_string" {
  type      = string
  sensitive = true
}

variable "auth_jwk_uri" {
  type = string
}

variable "cloudfront_origin_secret" {
  type      = string
  sensitive = true
}

# ---------------------------------------------------------------------------
# Messaging & Caching — runtime secrets
# Passed from root module; injected into Lambda environment blocks.
# ---------------------------------------------------------------------------

variable "redis_url" {
  type        = string
  sensitive   = true
  description = "Redis connection URL. Used by api-gateway, portfolio-service, and insight-service."
}

variable "kafka_bootstrap_servers" {
  type        = string
  description = "Kafka broker address. Used by portfolio-service, market-data-service, and insight-service."
}

variable "kafka_sasl_username" {
  type        = string
  sensitive   = true
  description = "Kafka SASL/PLAIN username."
}

variable "kafka_sasl_password" {
  type        = string
  sensitive   = true
  description = "Kafka SASL/PLAIN password."
}

variable "portfolio_function_url" {
  type    = string
  default = ""
}

variable "market_data_function_url" {
  type    = string
  default = ""
}

variable "insight_function_url" {
  type    = string
  default = ""
}

# When false (e.g. Neon + MongoDB Atlas + external Clerk), Lambdas must stay outside
# any VPC so they can reach the public internet without a NAT gateway.
# When true, set lambda_vpc_* to the same subnets/SGs as RDS/ElastiCache if those
# endpoints are only reachable in-VPC.
variable "enable_aws_managed_database" {
  type    = bool
  default = false
}

variable "lambda_vpc_subnet_ids" {
  type        = list(string)
  default     = []
  description = "Private subnets for Lambda ENIs; only used when enable_aws_managed_database is true and this list is non-empty."
}

variable "lambda_vpc_security_group_ids" {
  type        = list(string)
  default     = []
  description = "Security groups for Lambda ENIs; only used when enable_aws_managed_database is true and this list is non-empty."
}

variable "lambda_timeout" {
  type        = number
  default     = 60
  description = "Timeout in seconds for all Lambda functions. Must accommodate Spring Boot cold start."
}
