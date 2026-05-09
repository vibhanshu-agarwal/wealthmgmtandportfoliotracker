terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    # Bucket, table, and region are injected at init time via -backend-config flags
    # in CI (terraform.yml). The values below are the production defaults and are
    # overridden by those flags so local runs against a different bucket still work.
    bucket         = "vibhanshu-tf-state-2026"
    key            = "terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "vibhanshu-terraform-locks"
    encrypt        = true
  }
}
