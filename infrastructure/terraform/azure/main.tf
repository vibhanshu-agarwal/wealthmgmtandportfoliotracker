# =============================================================================
# main.tf — Azure Container Apps Deployment
# Wealth Management & Portfolio Tracker
#
# Resource ordering (Terraform dependency resolution):
#   1. Core resources (resource group, ACR, log analytics, telemetry workspace, App Insights, budget alert, ACA environment, SWA)
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
  daily_quota_gb      = 0.023
}

# Telemetry Log Analytics workspace — dedicated App Insights sink, distinct from
# wealth-${var.environment}-la so a telemetry ceiling cannot suppress ACA diagnostics.
resource "azurerm_log_analytics_workspace" "telemetry" {
  name                = "wealth-${var.environment}-telemetry-la"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  daily_quota_gb      = 0.023
}

# Application Insights — workspace-based Java component. The connection string
# lives only in Terraform state (encrypted remote backend); do not output it.
resource "azurerm_application_insights" "telemetry" {
  name                = "wealth-${var.environment}-ai"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  application_type    = "java"
  workspace_id        = azurerm_log_analytics_workspace.telemetry.id

  # Included 90-day interactive default for workspace-based App Insights (App*
  # tables), not a paid retention extension. Do not override App* onto Auxiliary/Basic.
  retention_in_days = 90

  # D3: local auth is required by the managed agent. Exit when the Entra ingestion
  # trigger is met (separate from GA; either order).
  local_authentication_enabled = true
}

# Cost Management budget — resource-group scope (not subscription). Notifications
# use Cost Management's free email channel; do not attach an Azure Monitor action group.
resource "azurerm_consumption_budget_resource_group" "main" {
  name              = "wealth-${var.environment}-rg-budget"
  resource_group_id = azurerm_resource_group.main.id

  amount     = 1100
  time_grain = "Monthly"

  # Azure requires first-of-month; 2026-08-01 is the month this budget was introduced.
  time_period {
    start_date = "2026-08-01T00:00:00Z"
  }

  notification {
    enabled        = true
    threshold      = 70
    threshold_type = "Actual"
    operator       = "GreaterThanOrEqualTo"
    contact_emails = var.budget_notification_emails
  }

  notification {
    enabled        = true
    threshold      = 100
    threshold_type = "Forecasted"
    operator       = "GreaterThanOrEqualTo"
    contact_emails = var.budget_notification_emails
  }
}

# Azure Container Apps Environment — shared networking and observability plane for all four services.
# Name pattern aligns with deploy-azure.yml env var: ACA_ENV=wealth-prod-aca-env
resource "azurerm_container_app_environment" "main" {
  name                       = "wealth-${var.environment}-aca-env"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
}

# AzureRM does not model this managed-agent block today, so there is no drift-fight
# with azurerm_container_app_environment / azurerm_application_insights. If a future
# AzureRM version adds openTelemetryConfiguration, migrate this AzAPI resource
# rather than running both.
#
# appLogsConfiguration is required on this PUT even though AzureRM already owns
# the environment's log-analytics destination. azapi_update_resource GET-merge-PUTs
# the environment; GET returns logAnalyticsConfiguration.sharedKey as null
# (write-only), and Azure rejects the PUT ("LogAnalyticsConfiguration is invalid")
# unless customerId + sharedKey are re-supplied. Destination is the Platform
# workspace (same as azurerm_container_app_environment.main), not telemetry.
resource "azapi_update_resource" "aca_otel_agent" {
  type        = "Microsoft.App/managedEnvironments@2025-10-02-preview"
  resource_id = azurerm_container_app_environment.main.id

  body = {
    properties = {
      appInsightsConfiguration = {
        connectionString = azurerm_application_insights.telemetry.connection_string
      }
      appLogsConfiguration = {
        destination = "log-analytics"
        logAnalyticsConfiguration = {
          customerId = azurerm_log_analytics_workspace.main.workspace_id
          sharedKey  = azurerm_log_analytics_workspace.main.primary_shared_key
        }
      }
      openTelemetryConfiguration = {
        tracesConfiguration = {
          destinations = ["appInsights"]
        }
      }
    }
  }
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

  lifecycle {
    ignore_changes = [
      # Azure records the GitHub repository association as computed attributes after the SWA
      # is connected via the portal. Terraform does not manage that connection: frontend
      # content is deployed by deploy-azure.yml using SWA_DEPLOYMENT_TOKEN (the external-
      # provider / deployment-token model). Re-declaring these fields would also require
      # managing repository_token — an admin-capable credential with no deployment benefit.
      # Ignoring both keeps the plan clean without touching the existing GitHub link.
      # Confirmed present via baseline remote-plan run 32655541821 (2026-08-23).
      repository_url,
      repository_branch,
    ]
  }
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

# GPT-4.1-mini deployment — model version 2025-04-14 (current stable, replaces deprecated gpt-4o-mini 2024-07-18).
# Capacity is var.openai_deployment_capacity (default 10 = 10K tokens/min).
resource "azurerm_cognitive_deployment" "gpt4o_mini" {
  name                 = "gpt-4o-mini"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "gpt-4.1-mini"
    version = "2025-04-14"
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
  seed_image          = var.use_seed_image ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : ""
  target_port         = var.use_seed_image ? 80 : 8080
  external_ingress    = true
  ingress_enabled     = var.api_gateway_ingress_enabled
  min_replicas        = var.api_gateway_min_replicas
  max_replicas        = 3
  cpu                 = 0.5
  memory              = "1Gi"

  # Non-sensitive env vars — service discovery URLs use bare app names (ACA internal DNS).
  env_vars = {
    SPRING_PROFILES_ACTIVE                                 = "prod,azure"
    PORTFOLIO_SERVICE_URL                                  = "http://portfolio-service"
    MARKET_DATA_SERVICE_URL                                = "http://market-data-service"
    INSIGHT_SERVICE_URL                                    = "http://insight-service"
    APP_CORS_ALLOWED_ORIGIN_PATTERNS                       = var.cors_allowed_origin_patterns
    MANAGEMENT_TRACING_EXPORT_ENABLED                      = "true"
    MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT = "grpc"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY                = "1.0"
    SERVICE_VERSION                                        = var.image_tag
    DEPLOYMENT_ENVIRONMENT_NAME                            = "prod"
  }

  # Sensitive env vars — values sourced from the secrets map below.
  # Keys must match keys in the secrets map (module derives secret name as lower(replace(key, "_", "-"))).
  secret_env_vars = {
    AUTH_JWT_SECRET            = var.auth_jwt_secret
    APP_AUTH_EMAIL             = var.app_auth_email
    APP_AUTH_PASSWORD          = var.app_auth_password
    APP_AUTH_USER_ID           = var.app_auth_user_id
    APP_AUTH_NAME              = var.app_auth_name
    SPRING_DATASOURCE_URL      = var.postgres_connection_string
    SPRING_DATASOURCE_USERNAME = var.postgres_username
    SPRING_DATASOURCE_PASSWORD = var.postgres_password
    REDIS_URL                  = var.redis_url
    INTERNAL_API_KEY           = var.internal_api_key
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME        = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD        = var.kafka_sasl_password
  }

  secrets = {
    AUTH_JWT_SECRET            = var.auth_jwt_secret
    APP_AUTH_EMAIL             = var.app_auth_email
    APP_AUTH_PASSWORD          = var.app_auth_password
    APP_AUTH_USER_ID           = var.app_auth_user_id
    APP_AUTH_NAME              = var.app_auth_name
    SPRING_DATASOURCE_URL      = var.postgres_connection_string
    SPRING_DATASOURCE_USERNAME = var.postgres_username
    SPRING_DATASOURCE_PASSWORD = var.postgres_password
    REDIS_URL                  = var.redis_url
    INTERNAL_API_KEY           = var.internal_api_key
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap_servers
    KAFKA_SASL_USERNAME        = var.kafka_sasl_username
    KAFKA_SASL_PASSWORD        = var.kafka_sasl_password
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
  seed_image          = var.use_seed_image ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : ""
  # target_port 8080 — all services listen on 8080 on Azure (application-prod.yml).
  # The 8081/8082/8083 scheme is only relevant to local dev (Docker Compose) where
  # all four Spring Boot processes share a host and therefore need distinct ports.
  # Inside ACA each service has its own internal FQDN, so port uniqueness is not required.
  target_port      = var.use_seed_image ? 80 : 8080
  external_ingress = false
  min_replicas     = 1
  max_replicas     = 3
  cpu              = 0.5
  memory           = "1Gi"

  env_vars = {
    SPRING_PROFILES_ACTIVE                                 = "prod,azure"
    MANAGEMENT_TRACING_EXPORT_ENABLED                      = "true"
    MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT = "grpc"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY                = "1.0"
    SERVICE_VERSION                                        = var.image_tag
    DEPLOYMENT_ENVIRONMENT_NAME                            = "prod"
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
# Persists to MongoDB Atlas; scheduled refresh runs via azurerm_container_app_job.market_data_refresh
# (daily ACA Job) — not via the long-lived container (@Scheduled disabled in application-azure.yml).
module "market_data_service" {
  source = "./modules/container-app"

  name                = "market-data-service"
  environment_id      = azurerm_container_app_environment.main.id
  resource_group_name = azurerm_resource_group.main.name
  acr_id              = azurerm_container_registry.main.id
  acr_login_server    = azurerm_container_registry.main.login_server
  image_repository    = "market-data-service"
  image_tag           = var.image_tag
  seed_image          = var.use_seed_image ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : ""
  # target_port 8080 — see comment in module.portfolio_service for rationale.
  target_port      = var.use_seed_image ? 80 : 8080
  external_ingress = false
  min_replicas     = 1
  max_replicas     = 3
  cpu              = 0.5
  memory           = "1Gi"

  env_vars = {
    SPRING_PROFILES_ACTIVE                                 = "prod,azure"
    MANAGEMENT_TRACING_EXPORT_ENABLED                      = "true"
    MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT = "grpc"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY                = "1.0"
    SERVICE_VERSION                                        = var.image_tag
    DEPLOYMENT_ENVIRONMENT_NAME                            = "prod"
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

# User-assigned managed identity for the refresh Job.
#
# WHY user-assigned (not system-assigned): a system-assigned identity only exists
# AFTER the Job resource is created, so the AcrPull role assignment that references
# `job.identity[0].principal_id` cannot be granted until the Job exists — yet the
# Job needs that grant to pull its image. Terraform resolves this by always touching
# the Job first when applying the role assignment (even with `-target`), which stalls
# on ACA Job revision provisioning. A user-assigned identity has a `principal_id`
# that is known as soon as the identity is created, so the AcrPull grant can be made
# BEFORE the Job is provisioned. This permanently breaks the bootstrap cycle.
resource "azurerm_user_assigned_identity" "market_data_refresh_job" {
  name                = "wealth-${var.environment}-mdrefresh-job-id"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
}

# market-data-service refresh Job — runs daily at 08:00 UTC.
# Boots the JVM, calls MarketDataRefreshService.refresh(), then exits cleanly.
# This is the sole production refresh path; the @Scheduled adapter is disabled
# in application-azure.yml (market-data.refresh.enabled: false).
resource "azurerm_container_app_job" "market_data_refresh" {
  name                         = "market-data-refresh-job"
  resource_group_name          = azurerm_resource_group.main.name
  location                     = azurerm_resource_group.main.location
  container_app_environment_id = azurerm_container_app_environment.main.id

  # Grant the UAMI's AcrPull role BEFORE the Job is created/replaced. The role
  # assignment binds to the UAMI (not this Job), so this dependency is acyclic — it
  # is what breaks the previous Job -> identity -> role -> Job bootstrap cycle that
  # stalled `terraform apply`.
  depends_on = [azurerm_role_assignment.market_data_refresh_job_acr_pull]

  # Match container-app module: centralindia revision provisioning can exceed 30m.
  # On first create/replace use a public seed image (use_seed_image=true); deploy-azure.yml
  # then rolls the real image via `az containerapp job update`, so apply never blocks on
  # an ACR pull (a scheduled Job pulls only when an execution is triggered, not at create).
  timeouts {
    create = "60m"
    update = "60m"
    delete = "30m"
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.market_data_refresh_job.id]
  }

  schedule_trigger_config {
    cron_expression          = "0 8 * * *"
    parallelism              = 1
    replica_completion_count = 1
  }

  replica_retry_limit        = 1
  replica_timeout_in_seconds = 600

  lifecycle {
    ignore_changes = [
      template[0].container[0].image,
    ]
  }

  # Pull from ACR using the user-assigned identity. For a user-assigned identity the
  # `identity` field is the UAMI resource ID (not "system", which selects the
  # system-assigned identity used by the long-running Container Apps).
  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.market_data_refresh_job.id
  }

  secret {
    name  = "spring-data-mongodb-uri"
    value = var.mongodb_connection_string
  }
  secret {
    name  = "kafka-bootstrap-servers"
    value = var.kafka_bootstrap_servers
  }
  secret {
    name  = "kafka-sasl-username"
    value = var.kafka_sasl_username
  }
  secret {
    name  = "kafka-sasl-password"
    value = var.kafka_sasl_password
  }
  secret {
    name  = "internal-api-key"
    value = var.internal_api_key
  }

  template {
    container {
      name = "market-data-refresh"
      # Seed bootstrap for the Job is driven by EITHER the global flag (first full
      # bootstrap) OR the Job-only flag (Job recovery without touching the apps).
      image = (var.use_seed_image || var.market_data_refresh_job_use_seed_image) ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : (
        "${azurerm_container_registry.main.login_server}/market-data-service:${var.image_tag}"
      )
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "prod,azure"
      }
      env {
        name  = "SPRING_MAIN_WEB_APPLICATION_TYPE"
        value = "none"
      }
      env {
        name = "MARKET_DATA_JOB_RUNNER_ENABLED"
        # Cutover checkpoint 9.3 (supported-asset-integrity Task 9): suspended for the
        # duration of the Postgres/Mongo repair (9.6/9.7). Restored to "true" at
        # checkpoint 9.11, through Terraform, after the Mongo repair reaches terminal
        # success. See .kiro/specs/supported-asset-integrity/tasks.md Task 9.
        value = "false"
      }
      env {
        name  = "MANAGEMENT_TRACING_EXPORT_ENABLED"
        value = "true"
      }
      env {
        name  = "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT"
        value = "grpc"
      }
      env {
        name  = "MANAGEMENT_TRACING_SAMPLING_PROBABILITY"
        value = "1.0"
      }
      env {
        name  = "SERVICE_VERSION"
        value = var.image_tag
      }
      env {
        name  = "DEPLOYMENT_ENVIRONMENT_NAME"
        value = "prod"
      }
      env {
        name  = "OTEL_SERVICE_NAME"
        value = "market-data-refresh-job"
      }

      env {
        name        = "SPRING_DATA_MONGODB_URI"
        secret_name = "spring-data-mongodb-uri"
      }
      env {
        name        = "KAFKA_BOOTSTRAP_SERVERS"
        secret_name = "kafka-bootstrap-servers"
      }
      env {
        name        = "KAFKA_SASL_USERNAME"
        secret_name = "kafka-sasl-username"
      }
      env {
        name        = "KAFKA_SASL_PASSWORD"
        secret_name = "kafka-sasl-password"
      }
      env {
        name        = "INTERNAL_API_KEY"
        secret_name = "internal-api-key"
      }
    }
  }
}

# AcrPull for the refresh Job's USER-ASSIGNED identity.
# Bound to the UAMI's principal_id (known at create time) rather than the Job's
# identity, so Terraform can grant the role before the Job exists. This is the fix
# for the circular dependency that previously forced a Job update on every targeted
# role-assignment apply and stalled on ACA Job revision provisioning.
resource "azurerm_role_assignment" "market_data_refresh_job_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.market_data_refresh_job.principal_id

  # The principal is a managed identity (service principal). Declaring the type lets the
  # provider skip the AAD existence check that can transiently fail with "PrincipalNotFound"
  # when the role is assigned immediately after the UAMI is created (replication lag).
  principal_type = "ServicePrincipal"
}

# market-data-service Mongo repair Job — Spec A task 7 / R3b.
# Manual trigger only. Repair property true, refresh property ABSENT (not false):
# false would start the suspended runner, whose SpringApplication.exit(0) can kill
# an in-flight repair. replica_retry_limit is 0 so FAILED_CONFLICT is not retried.
resource "azurerm_user_assigned_identity" "market_data_repair_job" {
  name                = "wealth-${var.environment}-mdrepair-job-id"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
}

resource "azurerm_container_app_job" "market_data_repair" {
  name                         = "market-data-repair-job"
  resource_group_name          = azurerm_resource_group.main.name
  location                     = azurerm_resource_group.main.location
  container_app_environment_id = azurerm_container_app_environment.main.id

  depends_on = [azurerm_role_assignment.market_data_repair_job_acr_pull]

  timeouts {
    create = "60m"
    update = "60m"
    delete = "30m"
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.market_data_repair_job.id]
  }

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  replica_retry_limit        = 0
  replica_timeout_in_seconds = 300

  lifecycle {
    ignore_changes = [
      template[0].container[0].image,
    ]
  }

  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.market_data_repair_job.id
  }

  secret {
    name  = "spring-data-mongodb-uri"
    value = var.mongodb_connection_string
  }
  secret {
    name  = "kafka-bootstrap-servers"
    value = var.kafka_bootstrap_servers
  }
  secret {
    name  = "kafka-sasl-username"
    value = var.kafka_sasl_username
  }
  secret {
    name  = "kafka-sasl-password"
    value = var.kafka_sasl_password
  }
  secret {
    name  = "internal-api-key"
    value = var.internal_api_key
  }

  template {
    container {
      name = "market-data-repair"
      image = (var.use_seed_image || var.market_data_repair_job_use_seed_image) ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : (
        "${azurerm_container_registry.main.login_server}/market-data-service:${var.image_tag}"
      )
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "prod,azure"
      }
      env {
        name  = "SPRING_MAIN_WEB_APPLICATION_TYPE"
        value = "none"
      }
      env {
        name  = "MARKET_DATA_REPAIR_ENABLED"
        value = "true"
      }
      env {
        name  = "MANAGEMENT_TRACING_EXPORT_ENABLED"
        value = "true"
      }
      env {
        name  = "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT"
        value = "grpc"
      }
      env {
        name  = "MANAGEMENT_TRACING_SAMPLING_PROBABILITY"
        value = "1.0"
      }
      env {
        name  = "SERVICE_VERSION"
        value = var.image_tag
      }
      env {
        name  = "DEPLOYMENT_ENVIRONMENT_NAME"
        value = "prod"
      }
      env {
        name  = "OTEL_SERVICE_NAME"
        value = "market-data-repair-job"
      }

      env {
        name        = "SPRING_DATA_MONGODB_URI"
        secret_name = "spring-data-mongodb-uri"
      }
      env {
        name        = "KAFKA_BOOTSTRAP_SERVERS"
        secret_name = "kafka-bootstrap-servers"
      }
      env {
        name        = "KAFKA_SASL_USERNAME"
        secret_name = "kafka-sasl-username"
      }
      env {
        name        = "KAFKA_SASL_PASSWORD"
        secret_name = "kafka-sasl-password"
      }
      env {
        name        = "INTERNAL_API_KEY"
        secret_name = "internal-api-key"
      }
    }
  }
}

resource "azurerm_role_assignment" "market_data_repair_job_acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.market_data_repair_job.principal_id
  principal_type       = "ServicePrincipal"
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
  seed_image          = var.use_seed_image ? "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest" : ""
  # target_port 8080 — see comment in module.portfolio_service for rationale.
  target_port      = var.use_seed_image ? 80 : 8080
  external_ingress = false
  min_replicas     = 1
  max_replicas     = 3
  cpu              = 0.5
  memory           = "1Gi"

  # AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_DEPLOYMENT are non-sensitive (no credentials).
  # They align 1:1 with the env-var names read by application-azure-ai.yml (task 4.3).
  # AZURE_OPENAI_API_KEY is deliberately absent — Managed Identity is the auth path.
  env_vars = {
    SPRING_PROFILES_ACTIVE                                 = "prod,azure,azure-ai"
    AZURE_OPENAI_ENDPOINT                                  = azurerm_cognitive_account.openai.endpoint
    AZURE_OPENAI_DEPLOYMENT                                = azurerm_cognitive_deployment.gpt4o_mini.name
    MANAGEMENT_TRACING_EXPORT_ENABLED                      = "true"
    MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT = "grpc"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY                = "1.0"
    SERVICE_VERSION                                        = var.image_tag
    DEPLOYMENT_ENVIRONMENT_NAME                            = "prod"
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
