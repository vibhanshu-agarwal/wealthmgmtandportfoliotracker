locals {
  localstack_endpoint = var.localstack_endpoint
}

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

terraform {
  backend "s3" {
    bucket         = "vibhanshu-tf-state-2026"
    key            = "terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "vibhanshu-terraform-locks"
    encrypt        = true
  }
}

# ---------------------------------------------------------------------------
# Artifact S3 bucket — stores Lambda deployment JARs
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "artifacts" {
  bucket = var.artifact_bucket_name
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# Compute module — Lambda functions + Function URLs
# ---------------------------------------------------------------------------

module "compute" {
  source = "./modules/compute"

  artifact_bucket_name          = var.artifact_bucket_name
  s3_key_api_gateway            = var.s3_key_api_gateway
  api_gateway_image_uri         = var.api_gateway_image_uri
  lambda_java_runtime           = var.lambda_java_runtime
  portfolio_memory_size         = var.portfolio_memory_size
  market_data_memory_size       = var.market_data_memory_size
  insight_service_memory_size   = var.insight_service_memory_size
  s3_key_portfolio              = var.s3_key_portfolio
  s3_key_market_data            = var.s3_key_market_data
  s3_key_insight                = var.s3_key_insight
  lambda_adapter_layer_arn      = var.lambda_adapter_layer_arn
  postgres_connection_string    = var.postgres_connection_string
  mongodb_connection_string     = var.mongodb_connection_string
  auth_jwk_uri                  = var.auth_jwk_uri
  cloudfront_origin_secret      = var.cloudfront_origin_secret
  enable_aws_managed_database   = var.enable_aws_managed_database
  lambda_vpc_subnet_ids         = var.lambda_vpc_subnet_ids
  lambda_vpc_security_group_ids = var.lambda_vpc_security_group_ids
  # Service-to-service URLs: populated via TF_VAR_* after first apply
  # (two-phase apply pattern — see README for details)
  portfolio_function_url   = var.portfolio_function_url
  market_data_function_url = var.market_data_function_url
  insight_function_url     = var.insight_function_url
}

# ---------------------------------------------------------------------------
# Database module — RDS PostgreSQL + ElastiCache Redis
# ---------------------------------------------------------------------------

module "database" {
  source = "./modules/database"

  project_name                = "wealth"
  is_local_dev                = var.use_localstack
  enable_aws_managed_database = var.enable_aws_managed_database
  db_username                 = var.db_username
  db_password                 = var.db_password
  rds_instance_class          = var.rds_instance_class
  rds_allocated_storage       = var.rds_allocated_storage
  elasticache_node_type       = var.elasticache_node_type
}

# ---------------------------------------------------------------------------
# Networking module — CloudFront distribution + optional Route 53 record
# ---------------------------------------------------------------------------

module "networking" {
  source = "./modules/cdn"

  # api-gateway Function URL is the CloudFront origin
  origin_url                              = module.compute.api_gateway_function_url
  static_site_bucket_regional_domain_name = "${var.frontend_bucket_name}.s3.${var.aws_region}.amazonaws.com"
  # CloudFront injects this header; api-gateway Spring filter validates it
  cloudfront_origin_secret = var.cloudfront_origin_secret
  domain_name              = var.domain_name
  acm_certificate_arn      = var.acm_certificate_arn
  route53_zone_id          = var.route53_zone_id
}
