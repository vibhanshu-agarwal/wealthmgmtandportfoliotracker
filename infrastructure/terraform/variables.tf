variable "use_localstack" {
  type        = bool
  default     = false
  description = "Toggle LocalStack vs. real AWS"
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

variable "lambda_adapter_layer_arn" {
  type        = string
  description = "ARN of the Lambda Web Adapter layer"
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
