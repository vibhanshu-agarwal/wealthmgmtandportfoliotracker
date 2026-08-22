# =============================================================================
# Outputs — container-app module
# =============================================================================

output "app_fqdn" {
  # try() rather than a bare index: azurerm_container_app.this.ingress is an empty list
  # whenever ingress_enabled is false (the maintenance-window dynamic block is omitted
  # entirely, see main.tf's own comment on that block), and a plain [0] index errors
  # ("Invalid index... the collection has no elements") rather than producing a usable
  # value. Nothing else in this module graph consumes app_fqdn (root outputs.tf surfaces
  # it purely for informational `terraform output`), so null is a safe value while
  # ingress is closed.
  value       = try(azurerm_container_app.this.ingress[0].fqdn, null)
  description = "Fully-qualified domain name of the Container App ingress. For external apps this is the public FQDN; for internal apps it is the internal FQDN within the ACA environment. Null while ingress_enabled is false."
}

output "identity_principal_id" {
  value       = azurerm_container_app.this.identity[0].principal_id
  description = "Object ID of the Container App's system-assigned managed identity. Used to assign Azure RBAC roles (e.g. Cognitive Services OpenAI User for insight-service)."
}
