locals {
  localstack_endpoint = var.localstack_endpoint

  # Single source of truth for Lambda Zip runtime + memory (api-gateway Image memory).
  # Override per field with the matching nullable root variable in variables.tf / tfvars / TF_VAR_*.
  lambda_defaults = {
    zip_java_runtime = "java21" # provider v5.100.x; switch when `java25` is listed
    # Lambda CPU allocation scales linearly with memory.
    # Spring Boot + AOT cold-start is CPU-bound: doubling memory halves init time.
    # market-data and insight were 1024 MB; raised to 2048 to reduce cold-start
    # duration and avoid LWA readiness timeout on first invocation.
    portfolio_memory_mb       = 2048
    market_data_memory_mb     = 2048
    insight_service_memory_mb = 2048
    api_gateway_memory_mb     = 2048
    lambda_timeout_seconds    = 60
  }
}
