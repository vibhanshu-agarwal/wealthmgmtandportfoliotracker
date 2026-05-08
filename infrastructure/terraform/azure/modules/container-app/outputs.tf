# =============================================================================
# Outputs — container-app module
# =============================================================================

output "app_fqdn" {
  value       = azurerm_container_app.this.ingress[0].fqdn
  description = "Fully-qualified domain name of the Container App ingress. For external apps this is the public FQDN; for internal apps it is the internal FQDN within the ACA environment."
}

output "identity_principal_id" {
  value       = azurerm_container_app.this.identity[0].principal_id
  description = "Object ID of the Container App's system-assigned managed identity. Used to assign Azure RBAC roles (e.g. Cognitive Services OpenAI User for insight-service)."
}
