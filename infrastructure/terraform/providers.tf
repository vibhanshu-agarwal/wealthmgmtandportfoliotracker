provider "aws" {
  region                      = var.aws_region
  access_key                  = var.use_localstack ? "test" : null
  secret_key                  = var.use_localstack ? "test" : null
  skip_credentials_validation = var.use_localstack
  skip_metadata_api_check     = var.use_localstack
  skip_requesting_account_id  = var.use_localstack
  s3_use_path_style           = var.use_localstack

  dynamic "endpoints" {
    for_each = var.use_localstack ? [1] : []
    content {
      lambda      = local.localstack_endpoint
      s3          = local.localstack_endpoint
      dynamodb    = local.localstack_endpoint
      cloudfront  = local.localstack_endpoint
      iam         = local.localstack_endpoint
      acm         = local.localstack_endpoint
      route53     = local.localstack_endpoint
      rds         = local.localstack_endpoint
      elasticache = local.localstack_endpoint
    }
  }
}
