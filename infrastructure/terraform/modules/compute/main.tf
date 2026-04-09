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
  runtime                        = "java21"
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_api_gateway
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = 512
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = merge(local.common_env, {
      PORTFOLIO_SERVICE_URL    = var.portfolio_function_url
      MARKET_DATA_SERVICE_URL  = var.market_data_function_url
      INSIGHT_SERVICE_URL      = var.insight_function_url
      AUTH_JWK_URI             = var.auth_jwk_uri
      CLOUDFRONT_ORIGIN_SECRET = var.cloudfront_origin_secret
    })
  }
}

resource "aws_lambda_function" "portfolio" {
  function_name                  = "wealth-portfolio-service"
  role                           = aws_iam_role.portfolio.arn
  runtime                        = "java21"
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_portfolio
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = 512
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
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
  runtime                        = "java21"
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_market_data
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = 512
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
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
  runtime                        = "java21"
  handler                        = "not.used"
  s3_bucket                      = var.artifact_bucket_name
  s3_key                         = var.s3_key_insight
  layers                         = [var.lambda_adapter_layer_arn]
  memory_size                    = 512
  timeout                        = 30
  publish                        = true
  reserved_concurrent_executions = 10

  snap_start {
    apply_on = "PublishedVersions"
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
