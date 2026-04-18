locals {
  localstack_endpoint = var.localstack_endpoint

  # Single source of truth for Lambda Zip runtime + memory (api-gateway Image memory).
  # Override per field with the matching nullable root variable in variables.tf / tfvars / TF_VAR_*.
  lambda_defaults = {
    zip_java_runtime          = "java21" # provider v5.100.x; switch when `java25` is listed
    portfolio_memory_mb       = 2048
    market_data_memory_mb     = 1024
    insight_service_memory_mb = 1024
    api_gateway_memory_mb     = 2048
    lambda_timeout_seconds    = 60
  }
}
