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
    # PR-validation path (terraform-azure.yml): terraform init -backend=false
    # Apply path: terraform init -backend-config=backend-azure.hcl
  }
}
