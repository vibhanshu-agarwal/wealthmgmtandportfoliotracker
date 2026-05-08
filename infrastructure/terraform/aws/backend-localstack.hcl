# Backend config for LocalStack — used with:
#   terraform init -reconfigure -backend-config=backend-localstack.hcl
# This points the S3 state backend at LocalStack instead of real AWS.

bucket                      = "wealth-tf-state-local"
region                      = "us-east-1"
use_lockfile                = true
access_key                  = "test"
secret_key                  = "test"
skip_credentials_validation = true
skip_metadata_api_check     = true
use_path_style              = true
skip_requesting_account_id  = true
skip_s3_checksum            = true

endpoints = {
  s3 = "http://localstack:4566"
}
