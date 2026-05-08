# Azure provider — authenticates via OIDC (Workload Identity Federation).
# No client_secret is used; the GitHub Actions workflow supplies the OIDC token
# via azure/login@v2 with client-id / tenant-id / subscription-id inputs only.
provider "azurerm" {
  features {}

  use_oidc        = true
  client_id       = var.azure_client_id
  tenant_id       = var.azure_tenant_id
  subscription_id = var.azure_subscription_id
}
