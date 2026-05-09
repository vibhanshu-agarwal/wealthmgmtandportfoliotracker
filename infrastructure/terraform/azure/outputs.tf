# =============================================================================
# Outputs — Azure Container Apps Deployment
# Wealth Management & Portfolio Tracker
# =============================================================================

output "api_gateway_fqdn" {
  value       = module.api_gateway.app_fqdn
  description = "Public ingress FQDN of the api-gateway Container App (used as the API origin by the Static Web App)."
}

output "acr_login_server" {
  value       = azurerm_container_registry.main.login_server
  description = "ACR login server (consumed by deploy-azure.yml when pushing service images)."
}

output "static_web_app_default_hostname" {
  value       = azurerm_static_web_app.frontend.default_host_name
  description = "Default *.azurestaticapps.net hostname for the frontend SWA. Use this value to set var.cors_allowed_origin_patterns after the first apply (e.g. 'https://<hostname>')."
}

# ---------------------------------------------------------------------------
# Internal FQDNs — useful for in-environment debugging.
# External callers MUST route through api_gateway_fqdn.
# ---------------------------------------------------------------------------

output "portfolio_service_internal_fqdn" {
  value       = module.portfolio_service.app_fqdn
  description = "Internal FQDN of portfolio-service (useful for in-environment debugging; external callers MUST route through api_gateway_fqdn)."
}

output "market_data_service_internal_fqdn" {
  value       = module.market_data_service.app_fqdn
  description = "Internal FQDN of market-data-service (useful for in-environment debugging; external callers MUST route through api_gateway_fqdn)."
}

output "insight_service_internal_fqdn" {
  value       = module.insight_service.app_fqdn
  description = "Internal FQDN of insight-service (useful for in-environment debugging; external callers MUST route through api_gateway_fqdn)."
}
