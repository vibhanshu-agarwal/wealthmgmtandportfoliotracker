# =============================================================================
# main.tf — container-app module
#
# Creates an Azure Container App with:
#   - System-assigned managed identity
#   - ACR pull via managed identity (no admin credentials)
#   - External or internal ingress
#   - Dynamic env vars and secrets injection
#   - AcrPull role assignment on the ACR
# =============================================================================

resource "azurerm_container_app" "this" {
  name                         = var.name
  container_app_environment_id = var.environment_id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  # Extend timeouts for regions with slower provisioning (e.g. centralindia).
  # Default create timeout is 30m which can be insufficient for initial revision provisioning.
  timeouts {
    create = "60m"
    update = "60m"
    delete = "30m"
  }

  # Image updates are handled exclusively by deploy-azure.yml via `az containerapp update`.
  # Ignoring only the image field prevents Terraform from triggering a new revision on
  # every apply (which causes false-failure polling timeouts in centralindia) while still
  # allowing Terraform to manage env vars, scaling, ingress, and all other template fields.
  lifecycle {
    ignore_changes = [
      template[0].container[0].image,
    ]
  }

  # System-assigned managed identity — used for ACR pull and (for insight-service)
  # for Azure OpenAI Managed Identity authentication.
  identity {
    type = "SystemAssigned"
  }

  # Pull images from ACR using the system-assigned identity (no admin password needed).
  registry {
    server   = var.acr_login_server
    identity = "system"
  }

  # Ingress configuration — external for api-gateway, internal for all other services.
  # Internal services are reachable within the ACA environment via http://<name>
  # (ACA maps port 80 → target_port automatically).
  ingress {
    external_enabled = var.external_ingress
    target_port      = var.target_port
    transport        = "auto"

    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    container {
      name   = var.name
      image  = var.seed_image != "" ? var.seed_image : "${var.acr_login_server}/${var.image_repository}:${var.image_tag}"
      cpu    = var.cpu
      memory = var.memory

      # Non-sensitive environment variables (plain values).
      dynamic "env" {
        for_each = var.env_vars
        content {
          name  = env.key
          value = env.value
        }
      }

      # Sensitive environment variables — values sourced from Container App secrets.
      # The secret name is derived as lower(replace(key, "_", "-")) to comply with
      # ACA secret naming rules (lowercase alphanumeric and hyphens only).
      dynamic "env" {
        for_each = var.secret_env_vars
        content {
          name        = env.key
          secret_name = lower(replace(env.key, "_", "-"))
        }
      }
    }
  }

  # Container App secrets — stored encrypted in ACA, referenced by env blocks above.
  # Secret names follow the same lower(replace(key, "_", "-")) convention.
  dynamic "secret" {
    for_each = var.secrets
    content {
      name  = lower(replace(secret.key, "_", "-"))
      value = secret.value
    }
  }
}

# Grant the Container App's system-assigned identity the AcrPull role on the ACR.
# This allows the ACA runtime to pull images without admin credentials.
# Without this role assignment, revision activation fails with UNAUTHORIZED.
resource "azurerm_role_assignment" "acr_pull" {
  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_container_app.this.identity[0].principal_id
}
