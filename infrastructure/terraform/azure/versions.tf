terraform {
  required_version = ">= 1.6.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  backend "azurerm" {
    # Backend values are injected at init time via -backend-config=backend-azure.hcl
    # (the real file is gitignored — see backend-azure.hcl.example for the template).
    #
    # PR-validation path (terraform-azure.yml): a backend_override.tf is written
    # at workflow time that replaces this azurerm backend with a local backend,
    # so plan can run without the AZURE_BACKEND_HCL secret.
    # Apply path: terraform init -backend-config=backend-azure.hcl
  }
}
