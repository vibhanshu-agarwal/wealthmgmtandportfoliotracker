# =============================================================================
# Variables — Azure Container Apps Deployment
# Wealth Management & Portfolio Tracker
# =============================================================================

# ---------------------------------------------------------------------------
# Azure identity (OIDC — no client_secret)
# Injected via TF_VAR_* from GitHub Actions secrets in terraform-azure.yml.
# ---------------------------------------------------------------------------

variable "azure_client_id" {
  type        = string
  description = "Azure AD application (client) ID used for OIDC authentication."
}

variable "azure_tenant_id" {
  type        = string
  description = "Azure AD tenant ID."
}

variable "azure_subscription_id" {
  type        = string
  description = "Azure subscription ID where all resources are provisioned."
}

# ---------------------------------------------------------------------------
# Cost Management
# ---------------------------------------------------------------------------

variable "budget_notification_emails" {
  type        = list(string)
  default     = ["vibhanshu.agarwal@outlook.com"]
  description = "Cost Management budget notification recipients (free email channel, not an Azure Monitor action group). Default is the subscription owner from az account show 2026-08-14; override via TF_VAR_budget_notification_emails."
}

# ---------------------------------------------------------------------------
# Deployment configuration
# ---------------------------------------------------------------------------

variable "environment" {
  type        = string
  default     = "prod"
  description = "Deployment environment name (e.g. prod, staging). Used as a suffix in resource names."
}

variable "location" {
  type        = string
  default     = "centralindia"
  description = "Azure region for the resource group and most resources. Static Web App uses a hardcoded region due to Free-tier availability constraints."
}

variable "openai_location" {
  type        = string
  default     = "eastus"
  description = "Azure region for the Azure OpenAI account. GPT-4o-mini provisioned throughput is available in eastus; check Azure OpenAI availability before changing."
}

variable "openai_deployment_capacity" {
  type        = number
  default     = 10
  description = "Provisioned throughput capacity (thousands of tokens per minute) for the GPT-4o-mini deployment. Dial up/down without editing main.tf."
}

variable "image_tag" {
  type        = string
  default     = "latest"
  description = "Container image tag to deploy (typically the git SHA from the CI pipeline, e.g. github.sha). Defaults to 'latest' for initial Terraform provisioning — deploy-azure.yml updates this after images are pushed to ACR."
}

variable "api_gateway_min_replicas" {
  type        = number
  default     = 0
  description = "Minimum replica count for api-gateway. Set to 1 to keep a warm instance (~$3-4/month extra); 0 for full scale-to-zero."
}

variable "api_gateway_ingress_enabled" {
  type        = bool
  default     = false
  description = "When false, omits the api-gateway ingress block so the maintenance window closes all routes including /api/internal/**. Default true. Changing this must update the existing app, not replace it. (Cutover checkpoint 9.5, supported-asset-integrity Task 9: closed for the duration of the Postgres/Mongo repair, 9.6/9.7. Restored to true at checkpoint 9.14.)"
}

variable "use_seed_image" {
  type        = bool
  default     = false
  description = "When true, all Container Apps use a public mcr.microsoft.com/azuredocs/containerapps-helloworld:latest seed image instead of the ACR image. Set to true for the initial 'terraform apply' before any images have been pushed to ACR. After deploy-azure.yml has pushed real images, set back to false (or omit — default is false)."
}

variable "market_data_refresh_job_use_seed_image" {
  type        = bool
  default     = false
  description = <<-EOT
    Job-scoped seed bootstrap for the market-data-refresh Job ONLY. When true, the Job
    uses the public seed image at create/replace time so a `-replace` recovery never blocks
    on an ACR pull. Unlike the global `use_seed_image`, this does NOT touch the four
    long-running Container Apps — so it cannot repoint their ingress `target_port` (which is
    NOT in the module's `ignore_changes`) from 8080 back to 80 and break live traffic.
    Set this (not the global flag) when recovering the Job; deploy-azure.yml rolls the real
    image afterward. Default false.
  EOT
}

variable "market_data_repair_job_use_seed_image" {
  type        = bool
  default     = false
  description = <<-EOT
    Job-scoped seed bootstrap for the market-data-repair Job ONLY. Same isolation as
    market_data_refresh_job_use_seed_image: does not retarget live Container App ingress.
    Default false.
  EOT
}

# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------

variable "auth_jwt_secret" {
  type        = string
  sensitive   = true
  description = "HS256 JWT signing/validation secret for the api-gateway. Must be at least 32 characters."

  validation {
    condition     = length(var.auth_jwt_secret) >= 32
    error_message = "auth_jwt_secret must be at least 32 characters for HS256."
  }
}

variable "app_auth_email" {
  type        = string
  sensitive   = true
  description = "Demo user login email injected into api-gateway as APP_AUTH_EMAIL."
}

variable "app_auth_password" {
  type        = string
  sensitive   = true
  description = "Demo user login password injected into api-gateway as APP_AUTH_PASSWORD."
}

variable "app_auth_user_id" {
  type        = string
  default     = "00000000-0000-0000-0000-000000000e2e"
  description = "Demo user ID injected into APP_AUTH_USER_ID. Must match the golden-state seeded portfolio user."
}

variable "app_auth_name" {
  type        = string
  default     = "Demo User"
  description = "Demo user display name injected into api-gateway as APP_AUTH_NAME."
}

# ---------------------------------------------------------------------------
# Database & cache secrets
# Injected via TF_VAR_* from GitHub Actions secrets in terraform-azure.yml.
# ---------------------------------------------------------------------------

variable "postgres_connection_string" {
  type        = string
  sensitive   = true
  description = "Neon PostgreSQL JDBC URL for portfolio-service (SPRING_DATASOURCE_URL)."
}

variable "postgres_username" {
  type        = string
  sensitive   = true
  description = "PostgreSQL username for portfolio-service (SPRING_DATASOURCE_USERNAME)."
}

variable "postgres_password" {
  type        = string
  sensitive   = true
  description = "PostgreSQL password for portfolio-service (SPRING_DATASOURCE_PASSWORD)."
}

variable "mongodb_connection_string" {
  type        = string
  sensitive   = true
  description = "MongoDB Atlas URI for market-data-service (SPRING_DATA_MONGODB_URI)."
}

variable "redis_url" {
  type        = string
  sensitive   = true
  description = "Upstash Redis connection URL (rediss://[:password@]host:port). Used by api-gateway rate limiting and insight-service sentiment cache."
}

# ---------------------------------------------------------------------------
# Messaging secrets (Aiven Kafka — shared with AWS path)
# ---------------------------------------------------------------------------

variable "kafka_bootstrap_servers" {
  type        = string
  description = "Aiven Kafka broker address (e.g. kafka-xxxxx.aivencloud.com:12345)."
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
# Internal API key (Golden-State E2E seeder)
# ---------------------------------------------------------------------------

variable "internal_api_key" {
  type        = string
  sensitive   = true
  description = "Shared secret gating /api/internal/** endpoints. Injected into every Container App as INTERNAL_API_KEY."
}

# ---------------------------------------------------------------------------
# Demo portfolio seed (Spec A checkpoint 9.12)
# ---------------------------------------------------------------------------

variable "demo_seed_on_startup" {
  type        = bool
  default     = false
  description = "Temporary Spec A 9.12 gate for DemoPortfolioInitializer. Steady state is false; only dedicated 9.12 workflow profiles may override it."
}

variable "demo_tx_diagnostics" {
  type        = bool
  default     = false
  description = "Temporary Spec A 9.12 RCA gate for startup transaction diagnostics. Steady state is false; only dedicated tx-diag workflow profiles may override it."
}

# ---------------------------------------------------------------------------
# CORS
# ---------------------------------------------------------------------------

variable "cors_allowed_origin_patterns" {
  type        = string
  default     = "https://vibhanshu-ai-portfolio.dev,https://www.vibhanshu-ai-portfolio.dev,https://salmon-sand-00357bb10.7.azurestaticapps.net"
  description = "Comma-separated list of allowed CORS origin patterns for the api-gateway. Includes custom domains + SWA default hostname during transition. Narrow to custom domains only after a few days."
}
