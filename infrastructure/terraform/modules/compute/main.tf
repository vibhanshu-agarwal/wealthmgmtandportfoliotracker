# =============================================================================
# Compute Module — IAM Roles, Lambda Functions, Aliases, Function URLs
# =============================================================================

locals {
  common_env = {
    JAVA_TOOL_OPTIONS       = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    SPRING_PROFILES_ACTIVE  = "aws"
    AWS_LAMBDA_EXEC_WRAPPER = "/opt/bootstrap"
    PORT                    = "8080"
  }

  # api-gateway is deployed as a container image (Dockerfile bundles Lambda Web Adapter).
  # Do not set AWS_LAMBDA_EXEC_WRAPPER — the image ENTRYPOINT runs the adapter.
  api_gateway_container_env = {
    JAVA_TOOL_OPTIONS      = local.common_env.JAVA_TOOL_OPTIONS
    SPRING_PROFILES_ACTIVE = local.common_env.SPRING_PROFILES_ACTIVE
  }

  # VPC attachment only when managed AWS DB is on AND operators supplied subnets/SGs.
  # Otherwise omit vpc_config entirely so Lambdas use the default (public) network path
  # (Atlas, Clerk, Neon, etc. over the internet — avoids 504s from private subnet + no NAT).
  attach_lambda_vpc = var.enable_aws_managed_database && length(var.lambda_vpc_subnet_ids) > 0 && length(var.lambda_vpc_security_group_ids) > 0
}

# ---------------------------------------------------------------------------
# IAM Execution Roles
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "api_gateway" {
  name               = "wealth-api-gateway-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role" "portfolio" {
  name               = "wealth-portfolio-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role" "market_data" {
  name               = "wealth-market-data-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role" "insight" {
  name               = "wealth-insight-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "api_gateway_basic" {
  role       = aws_iam_role.api_gateway.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "api_gateway_ecr_readonly" {
  role       = aws_iam_role.api_gateway.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_role_policy_attachment" "portfolio_basic" {
  role       = aws_iam_role.portfolio.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "market_data_basic" {
  role       = aws_iam_role.market_data.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "insight_basic" {
  role       = aws_iam_role.insight.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Required only when Lambdas run inside a VPC (ENI management + optional VPC flow logs).
resource "aws_iam_role_policy_attachment" "api_gateway_vpc" {
  count      = local.attach_lambda_vpc ? 1 : 0
  role       = aws_iam_role.api_gateway.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy_attachment" "portfolio_vpc" {
  count      = local.attach_lambda_vpc ? 1 : 0
  role       = aws_iam_role.portfolio.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy_attachment" "market_data_vpc" {
  count      = local.attach_lambda_vpc ? 1 : 0
  role       = aws_iam_role.market_data.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy_attachment" "insight_vpc" {
  count      = local.attach_lambda_vpc ? 1 : 0
  role       = aws_iam_role.insight.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# Insight service needs Bedrock access (scoped to specific model, no wildcard)
resource "aws_iam_role_policy" "insight_bedrock" {
  name = "insight-bedrock-invoke"
  role = aws_iam_role.insight.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["bedrock:InvokeModel"]
        Resource = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-3-haiku-20240307-v1:0"
      }
    ]
  })
}

# ---------------------------------------------------------------------------
# Lambda Functions
# ---------------------------------------------------------------------------

resource "aws_lambda_function" "api_gateway" {
  function_name                  = "wealth-api-gateway"
  role                           = aws_iam_role.api_gateway.arn
  package_type                   = "Image"
  image_uri                      = var.api_gateway_image_uri
  architectures                  = ["x86_64"]
  memory_size                    = var.api_gateway_memory
  timeout                        = 30 # seconds; keep >= 30 for Spring init + adapter readiness
  publish                        = true
  reserved_concurrent_executions = 10

  dynamic "vpc_config" {
    for_each = local.attach_lambda_vpc ? [1] : []
    content {
      subnet_ids         = var.lambda_vpc_subnet_ids
      security_group_ids = var.lambda_vpc_security_group_ids
    }
  }

  environment {
    variables = merge(local.api_gateway_container_env, {
      # Lambda Web Adapter polls PORT; Spring Boot uses SERVER_PORT — must match api-gateway Dockerfile (8080).
      SERVER_PORT              = "8080"
      PORT                     = "8080"
      PORTFOLIO_SERVICE_URL    = var.portfolio_function_url
      MARKET_DATA_SERVICE_URL  = var.market_data_function_url
      INSIGHT_SERVICE_URL      = var.insight_function_url
      AUTH_JWK_URI             = var.auth_jwk_uri
      CLOUDFRONT_ORIGIN_SECRET = var.cloudfront_origin_secret
    })
  }

  # deploy.yml updates the image digest/tag in AWS; Terraform keeps package_type Image + role/env.
  lifecycle {
    ignore_changes = [
      image_uri,
    ]
  }
}

resource "aws_lambda_function" "portfolio" {
  function_name                  = "wealth-portfolio-service"
  role                           = aws_iam_role.portfolio.arn
  runtime                        = var.lambda_java_runtime
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_portfolio
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = var.portfolio_memory_size
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
  }

  dynamic "vpc_config" {
    for_each = local.attach_lambda_vpc ? [1] : []
    content {
      subnet_ids         = var.lambda_vpc_subnet_ids
      security_group_ids = var.lambda_vpc_security_group_ids
    }
  }

  environment {
    variables = merge(local.common_env, {
      SPRING_DATASOURCE_URL = var.postgres_connection_string
    })
  }
}

resource "aws_lambda_function" "market_data" {
  function_name                  = "wealth-market-data-service"
  role                           = aws_iam_role.market_data.arn
  runtime                        = var.lambda_java_runtime
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_market_data
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = var.market_data_memory_size
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
  }

  dynamic "vpc_config" {
    for_each = local.attach_lambda_vpc ? [1] : []
    content {
      subnet_ids         = var.lambda_vpc_subnet_ids
      security_group_ids = var.lambda_vpc_security_group_ids
    }
  }

  environment {
    variables = merge(local.common_env, {
      SPRING_DATA_MONGODB_URI = var.mongodb_connection_string
    })
  }
}

resource "aws_lambda_function" "insight" {
  function_name                  = "wealth-insight-service"
  role                           = aws_iam_role.insight.arn
  runtime                        = var.lambda_java_runtime
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_insight
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = var.insight_service_memory_size
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
  }

  dynamic "vpc_config" {
    for_each = local.attach_lambda_vpc ? [1] : []
    content {
      subnet_ids         = var.lambda_vpc_subnet_ids
      security_group_ids = var.lambda_vpc_security_group_ids
    }
  }

  environment {
    variables = local.common_env
  }
}

# ---------------------------------------------------------------------------
# Lambda Aliases — "live" alias points to published version (required for SnapStart)
# ---------------------------------------------------------------------------

resource "aws_lambda_alias" "api_gateway_live" {
  name             = "live"
  function_name    = aws_lambda_function.api_gateway.function_name
  function_version = aws_lambda_function.api_gateway.version
}

resource "aws_lambda_alias" "portfolio_live" {
  name             = "live"
  function_name    = aws_lambda_function.portfolio.function_name
  function_version = aws_lambda_function.portfolio.version
}

resource "aws_lambda_alias" "market_data_live" {
  name             = "live"
  function_name    = aws_lambda_function.market_data.function_name
  function_version = aws_lambda_function.market_data.version
}

resource "aws_lambda_alias" "insight_live" {
  name             = "live"
  function_name    = aws_lambda_function.insight.function_name
  function_version = aws_lambda_function.insight.version
}

# ---------------------------------------------------------------------------
# Function URLs — attached to "live" alias (NOT $LATEST)
# ---------------------------------------------------------------------------

resource "aws_lambda_function_url" "api_gateway" {
  function_name      = aws_lambda_function.api_gateway.function_name
  qualifier          = aws_lambda_alias.api_gateway_live.name
  authorization_type = "NONE"
}

resource "aws_lambda_function_url" "portfolio" {
  function_name      = aws_lambda_function.portfolio.function_name
  qualifier          = aws_lambda_alias.portfolio_live.name
  authorization_type = "NONE"
}

resource "aws_lambda_function_url" "market_data" {
  function_name      = aws_lambda_function.market_data.function_name
  qualifier          = aws_lambda_alias.market_data_live.name
  authorization_type = "NONE"
}

resource "aws_lambda_function_url" "insight" {
  function_name      = aws_lambda_function.insight.function_name
  qualifier          = aws_lambda_alias.insight_live.name
  authorization_type = "NONE"
}
