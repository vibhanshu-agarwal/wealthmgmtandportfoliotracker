# =============================================================================
# main.tf — Azure Container Apps Deployment
# Wealth Management & Portfolio Tracker
#
# Resource ordering (Terraform dependency resolution):
#   1. Core resources (resource group, ACR, log analytics, ACA environment, SWA)
#   2. Azure OpenAI account + deployment
#   3. Four Container App module blocks
#   4. Role assignment: insight-service → Azure OpenAI (references module output)
# =============================================================================

# ---------------------------------------------------------------------------
# 1. Core resources
# ---------------------------------------------------------------------------

# Resource group — all Azure resources for this deployment live here.
# Name pattern aligns with deploy-azure.yml env var: AZURE_RG=wealth-azure-prod-rg
resource "azurerm_resource_group" "main" {
  name     = "wealth-azure-${var.environment}-rg"
  location = var.location
}

# Azure Container Registry (Basic SKU, admin disabled — images pulled via managed identity).
# Name pattern aligns with deploy-azure.yml env var: ACR_NAME=wealthprodacr
# ACR names must be alphanumeric only (no separators), 5–50 chars, globally unique.
resource "azurerm_container_registry" "main" {
  name                = "wealth${var.environment}acr"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Basic"
  admin_enabled       = false
}

# Log Analytics workspace — required by the ACA environment for structured logging.
resource "azurerm_log_analytics_workspace" "main" {
  name                = "wealth-${var.environment}-la"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
}

# Azure Container Apps Environment — shared networking and observability plane for all four services.
# Name pattern aligns with deploy-azure.yml env var: ACA_ENV=wealth-prod-aca-env
resource "azurerm_container_app_environment" "main" {
  name                       = "wealth-${var.environment}-aca-env"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
}

# Static Web App — hosts the Next.js static export (frontend).
# Location is hardcoded to "centralus" because the Free tier is only available in
# a limited set of regions (centralus, eastus2, westus2, westeurope, eastasia, eastasiastagehk).
# Do NOT use var.location here — centralindia is not supported for SWA Free tier.
resource "azurerm_static_web_app" "frontend" {
  name                = "wealth-${var.environment}-swa"
  resource_group_name = azurerm_resource_group.main.name
  location            = "centralus" # SWA Free tier region constraint — do not change to var.location
  sku_tier            = "Free"
  sku_size            = "Free"
}

# ---------------------------------------------------------------------------
# 2. Azure OpenAI account + deployment
# ---------------------------------------------------------------------------

# Azure OpenAI account — provisioned in var.openai_location (eastus by default)
# because GPT-4o-mini provisioned throughput is not available in all regions.
resource "azurerm_cognitive_account" "openai" {
  name                = "wealth-${var.environment}-aoai"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.openai_location
  kind                = "OpenAI"
  sku_name            = "S0"

  # Managed Identity auth is the default path (see application-azure-ai.yml).
  # API-key auth is documented as an opt-in alternative; no key is exposed here.
}

# GPT-4o-mini deployment — model version 2024-07-18 (stable, widely available).
# Capacity is var.openai_deployment_capacity (default 10 = 10K tokens/min).
resource "azurerm_cognitive_deployment" "gpt4o_mini" {
  name                 = "gpt-4o-mini"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "gpt-4o-mini"
    version = "2024-07-18"
  }

  sku {
    name     = "Standard"
    capacity = var.openai_deployment_capacity
  }
}

# ---------------------------------------------------------------------------
# 3. Container App modules
# ---------------------------------------------------------------------------

# api-gateway — external ingress, routes all client traffic to downstream services.
# SPRING_PROFILES_ACTIVE=prod,azure activates the azure YAML overlay (CORS, Redis health).
module "api_gateway" {
  source = "./modules/container-app"

  name                = "api-gateway"
  environment_id      = azurerm_container_app_environment.main.id
  resource_group_name = azurerm_resource_group.main.name
  acr_id              = azurerm_container_registry.main.id
  acr_login_server    = azurerm_container_registry.main.login_server
  image_repository    = "api-gateway"
  image_tag           = var.image_tag
  target_port         = 8080
  external_ingress    = true
  min_replicas        = var.api_gateway_min_replicas
  max_replicas        = 3
  cpu                 = 0.5
  memory              = "1Gi"

  # Non-sensitive env vars — service discovery URLs use bare app names (ACA internal DNS).
  env_vars = {
    SPRING_PROFILES_ACTIVE  = "prod,azure"
    PORTFOLIO_SERVICE_URL   = "http://portfolio-service"
    MARKET_DATA_SERVICE_URL = "http://market-data-service"
    INSIGHT_SERVICE_URL     = "http://insight-service"
  }

  # Sensitive env vars — values sourced from the secrets map below.
  # Keys must match keys in the secrets map (module derives secret name as lower(replace(key, "_", "-"))).
  secret_env_vars = {
    AUTH_JWT_SECRET         = var.auth_jwt_secret
    APP_AUTH_EMAIL          = var.app_auth_email
    APP_AUTH_PASSWORD       = var.app_auth_password
    APP_AUTH_USER_ID        = var.app_auth_user_id
    APP_AUTH_NAME           = var.app_auth_name
    REDIS_URL               = var.redis_url
    INTERNAL_API_KEY        = var.internal_api_key
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
  }

  secrets = {
    AUTH_JWT_SECRET         = var.auth_jwt_secret
    APP_AUTH_EMAIL          = var.app_auth_email
    APP_AUTH_PASSWORD       = var.app_auth_password
    APP_AUTH_USER_ID        = var.app_auth_user_id
    APP_AUTH_NAME           = var.app_auth_name
    REDIS_URL               = var.redis_url
    INTERNAL_API_KEY        = var.internal_api_key
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
  }
}

# portfolio-service — internal ingress, manages portfolio holdings and valuations.
# Consumes Kafka price-updated events; persists to Neon PostgreSQL.
module "portfolio_service" {
  source = "./modules/container-app"

  name                = "portfolio-service"
  environment_id      = azurerm_container_app_environment.main.id
  resource_group_name = azurerm_resource_group.main.name
  acr_id              = azurerm_container_registry.main.id
  acr_login_server    = azurerm_container_registry.main.login_server
  image_repository    = "portfolio-service"
  image_tag           = var.image_tag
  target_port         = 8081
  external_ingress    = false
  min_replicas        = 0
  max_replicas        = 3
  cpu                 = 0.5
  memory              = "1Gi"

  env_vars = {
    SPRING_PROFILES_ACTIVE = "prod,azure"
  }

  secret_env_vars = {
    SPRING_DATASOURCE_URL      = var.postgres_connection_string
    SPRING_DATASOURCE_USERNAME = var.postgres_username
    SPRING_DATASOURCE_PASSWORD = var.postgres_password
    REDIS_URL                  = var.redis_url
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME        = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD        = var.kafka_sasl_password
    INTERNAL_API_KEY           = var.internal_api_key
  }

  secrets = {
    SPRING_DATASOURCE_URL      = var.postgres_connection_string
    SPRING_DATASOURCE_USERNAME = var.postgres_username
    SPRING_DATASOURCE_PASSWORD = var.postgres_password
    REDIS_URL                  = var.redis_url
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME        = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD        = var.kafka_sasl_password
    INTERNAL_API_KEY           = var.internal_api_key
  }
}

# market-data-service — internal ingress, fetches and streams market prices via Kafka.
# Persists to MongoDB Atlas; scheduled refresh is enabled on ACA (long-lived containers).
module "market_data_service" {
  source = "./modules/container-app"

  name                = "market-data-service"
  environment_id      = azurerm_container_app_environment.main.id
  resource_group_name = azurerm_resource_group.main.name
  acr_id              = azurerm_container_registry.main.id
  acr_login_server    = azurerm_container_registry.main.login_server
  image_repository    = "market-data-service"
  image_tag           = var.image_tag
  target_port         = 8082
  external_ingress    = false
  min_replicas        = 0
  max_replicas        = 3
  cpu                 = 0.5
  memory              = "1Gi"

  env_vars = {
    SPRING_PROFILES_ACTIVE = "prod,azure"
  }

  secret_env_vars = {
    SPRING_DATA_MONGODB_URI = var.mongodb_connection_string
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
    INTERNAL_API_KEY        = var.internal_api_key
  }

  secrets = {
    SPRING_DATA_MONGODB_URI = var.mongodb_connection_string
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
    INTERNAL_API_KEY        = var.internal_api_key
  }
}

# insight-service — internal ingress, generates AI insights via Azure OpenAI.
# SPRING_PROFILES_ACTIVE=prod,azure,azure-ai activates both the azure overlay and
# the azure-ai overlay (Azure OpenAI endpoint + deployment config).
#
# Authentication to Azure OpenAI uses the system-assigned managed identity
# (Cognitive Services OpenAI User role — see azurerm_role_assignment.insight_openai below).
# AZURE_OPENAI_API_KEY is intentionally NOT set here; Managed Identity is the default path.
module "insight_service" {
  source = "./modules/container-app"

  name                = "insight-service"
  environment_id      = azurerm_container_app_environment.main.id
  resource_group_name = azurerm_resource_group.main.name
  acr_id              = azurerm_container_registry.main.id
  acr_login_server    = azurerm_container_registry.main.login_server
  image_repository    = "insight-service"
  image_tag           = var.image_tag
  target_port         = 8083
  external_ingress    = false
  min_replicas        = 0
  max_replicas        = 3
  cpu                 = 0.5
  memory              = "1Gi"

  # AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_DEPLOYMENT are non-sensitive (no credentials).
  # They align 1:1 with the env-var names read by application-azure-ai.yml (task 4.3).
  # AZURE_OPENAI_API_KEY is deliberately absent — Managed Identity is the auth path.
  env_vars = {
    SPRING_PROFILES_ACTIVE  = "prod,azure,azure-ai"
    AZURE_OPENAI_ENDPOINT   = azurerm_cognitive_account.openai.endpoint
    AZURE_OPENAI_DEPLOYMENT = azurerm_cognitive_deployment.gpt4o_mini.name
  }

  secret_env_vars = {
    REDIS_URL               = var.redis_url
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
    INTERNAL_API_KEY        = var.internal_api_key
  }

  secrets = {
    REDIS_URL               = var.redis_url
    KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME     = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD     = var.kafka_sasl_password
    INTERNAL_API_KEY        = var.internal_api_key
  }
}

# ---------------------------------------------------------------------------
# 4. Role assignment: insight-service → Azure OpenAI
# Must come after the module block so module.insight_service.identity_principal_id
# is available. Terraform resolves the dependency automatically.
# ---------------------------------------------------------------------------

# Grant insight-service's managed identity the Cognitive Services OpenAI User role
# on the Azure OpenAI account. This allows DefaultAzureCredential to authenticate
# without an API key — the recommended production auth path.
resource "azurerm_role_assignment" "insight_openai" {
  scope                = azurerm_cognitive_account.openai.id
  role_definition_name = "Cognitive Services OpenAI User"
  principal_id         = module.insight_service.identity_principal_id
}
