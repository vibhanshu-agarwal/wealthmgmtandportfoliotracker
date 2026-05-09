# =============================================================================
# Variables — container-app module
# Reusable module for deploying a Spring Boot service as an Azure Container App
# with system-assigned managed identity and ACR pull authorization.
# =============================================================================

variable "name" {
  type        = string
  description = "Name of the Container App (used as the internal DNS hostname within the ACA environment)."
}

variable "environment_id" {
  type        = string
  description = "Resource ID of the Azure Container Apps Environment."
}

variable "resource_group_name" {
  type        = string
  description = "Name of the resource group where the Container App is deployed."
}

variable "acr_id" {
  type        = string
  description = "Resource ID of the Azure Container Registry. Used to scope the AcrPull role assignment."
}

variable "acr_login_server" {
  type        = string
  description = "Login server hostname of the ACR (e.g. wealthprodacr.azurecr.io). Used to construct the full image reference."
}

variable "image_repository" {
  type        = string
  description = "Image repository name within the ACR (e.g. api-gateway, portfolio-service)."
}

variable "image_tag" {
  type        = string
  description = "Image tag to deploy (typically the git SHA from the CI pipeline)."
}

variable "target_port" {
  type        = number
  description = "Port the container listens on (e.g. 8080 for api-gateway, 8081 for portfolio-service)."
}

variable "external_ingress" {
  type        = bool
  default     = false
  description = "When true, the Container App is accessible from the public internet. Only api-gateway should set this to true."
}

variable "min_replicas" {
  type        = number
  default     = 0
  description = "Minimum replica count. 0 = scale-to-zero (cost-optimal). Set to 1 for a warm instance."
}

variable "max_replicas" {
  type        = number
  default     = 3
  description = "Maximum replica count for horizontal scaling."
}

variable "cpu" {
  type        = number
  default     = 0.5
  description = "CPU allocation per replica in vCPU units (e.g. 0.5 = half a vCPU)."
}

variable "memory" {
  type        = string
  default     = "1Gi"
  description = "Memory allocation per replica (e.g. '1Gi'). Must be compatible with the cpu value per ACA resource rules."
}

variable "env_vars" {
  type        = map(string)
  default     = {}
  description = "Non-sensitive environment variables to inject into the container (key = env var name, value = plain string value)."
}

variable "secret_env_vars" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = "Sensitive environment variables whose values are sourced from Container App secrets. Keys must match keys in var.secrets (the module derives the secret name as lower(replace(key, '_', '-')))."
}

variable "secrets" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = "Container App secrets (key = logical name used in secret_env_vars, value = secret value). The secret name stored in ACA is lower(replace(key, '_', '-'))."
}
