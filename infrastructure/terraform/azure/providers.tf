# Azure provider — authenticates via OIDC (Workload Identity Federation).
# No client_secret is used; the GitHub Actions workflow supplies the OIDC token
# via azure/login@v2 with client-id / tenant-id / subscription-id inputs only.
provider "azurerm" {
  features {}

  use_oidc        = true
  client_id       = var.azure_client_id
  tenant_id       = var.azure_tenant_id
  subscription_id = var.azure_subscription_id

  # The CI service principal does not have subscription-scope permission to
  # register Resource Providers. The required RPs (Microsoft.App,
  # Microsoft.OperationalInsights, Microsoft.ContainerRegistry,
  # Microsoft.CognitiveServices, Microsoft.Web) are pre-registered in the
  # target subscription, so auto-registration is disabled here.
  resource_provider_registrations = "none"
}

# AzAPI — same OIDC/Workload Identity as azurerm. Used by
# azapi_update_resource.aca_otel_agent to patch the ACA environment's managed
# OpenTelemetry agent (AzureRM does not model that block).
provider "azapi" {
  use_oidc        = true
  client_id       = var.azure_client_id
  tenant_id       = var.azure_tenant_id
  subscription_id = var.azure_subscription_id
}
