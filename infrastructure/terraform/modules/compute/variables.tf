# Compute module variables — stub (populated in Task 4)

variable "artifact_bucket_name" {
  type = string
}

variable "s3_key_api_gateway" {
  type = string
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
