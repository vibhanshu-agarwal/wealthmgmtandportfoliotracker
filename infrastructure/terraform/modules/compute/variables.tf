# =============================================================================
# Compute Module Variables
# Authoritative descriptions, defaults, and validation live in the root
# variables.tf. This file declares only type (+ sensitive where applicable).
# =============================================================================

variable "artifact_bucket_name" {
  type = string
}

variable "api_gateway_image_uri" {
  type = string
}

# ---------------------------------------------------------------------------
# Service image URIs — all three backend services use Image package type
# ---------------------------------------------------------------------------

variable "portfolio_image_uri" {
  type = string
}

variable "market_data_image_uri" {
  type = string
}

variable "insight_image_uri" {
  type = string
}

variable "api_gateway_memory" {
  type = number
}

variable "portfolio_memory_size" {
  type = number
}

variable "market_data_memory_size" {
  type = number
}

variable "insight_service_memory_size" {
  type = number
}

variable "postgres_connection_string" {
  type      = string
  sensitive = true
}

variable "postgres_username" {
  type      = string
  sensitive = true
}

variable "postgres_password" {
  type      = string
  sensitive = true
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
# ---------------------------------------------------------------------------

variable "redis_url" {
  type      = string
  sensitive = true
}

variable "kafka_bootstrap_servers" {
  type = string
}

variable "kafka_sasl_username" {
  type      = string
  sensitive = true
}

variable "kafka_sasl_password" {
  type      = string
  sensitive = true
}

variable "internal_api_key" {
  type      = string
  sensitive = true
}

variable "portfolio_function_url" {
  type = string
}

variable "market_data_function_url" {
  type = string
}

variable "insight_function_url" {
  type = string
}

variable "enable_aws_managed_database" {
  type = bool
}

variable "lambda_vpc_subnet_ids" {
  type = list(string)
}

variable "lambda_vpc_security_group_ids" {
  type = list(string)
}

variable "lambda_timeout" {
  type = number
}

variable "lambda_architecture" {
  type = string

  validation {
    condition     = contains(["arm64", "x86_64"], var.lambda_architecture)
    error_message = "lambda_architecture must be \"arm64\" or \"x86_64\"."
  }
}

variable "enable_provisioned_concurrency" {
  type = bool
}
