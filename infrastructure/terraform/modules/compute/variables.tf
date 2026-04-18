# Compute module variables — stub (populated in Task 4)

variable "artifact_bucket_name" {
  type = string
}

variable "s3_key_api_gateway" {
  type        = string
  description = "S3 object key for api-gateway JAR (unused when api-gateway uses package_type Image)"
}

variable "api_gateway_image_uri" {
  type        = string
  description = "Full ECR image URI for api-gateway Lambda (package_type Image), e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo:latest"
}

variable "lambda_java_runtime" {
  type        = string
  description = "AWS Lambda managed runtime for Zip-based Java Lambdas (e.g. java21; java25 when provider/AWS list includes it)."
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

variable "s3_key_portfolio" {
  type = string
}

variable "s3_key_market_data" {
  type = string
}

variable "s3_key_insight" {
  type = string
}

variable "lambda_adapter_layer_arn" {
  type = string
}

variable "postgres_connection_string" {
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
