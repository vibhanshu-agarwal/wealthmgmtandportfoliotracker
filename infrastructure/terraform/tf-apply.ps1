# tf-apply.ps1 — Apply the saved Terraform plan
# Run from repo root: pwsh infrastructure/terraform/tf-apply.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Read secrets from .env.secrets
$envFile = Join-Path $PSScriptRoot "..\..\\.env.secrets"
$secrets = Get-Content $envFile | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' }

foreach ($line in $secrets) {
    $parts = $line -split '=', 2
    $key = $parts[0].Trim()
    $val = if ($parts.Length -gt 1) { $parts[1].Trim() } else { "" }
    switch ($key) {
        "POSTGRES_CONNECTION_STRING" { $env:TF_VAR_postgres_connection_string = $val }
        "MONGODB_CONNECTION_STRING"  { $env:TF_VAR_mongodb_connection_string = $val }
        "AUTH_JWT_SECRET"            { $env:TF_VAR_auth_jwt_secret = $val }
        "E2E_TEST_USER_EMAIL"        { $env:TF_VAR_app_auth_email = $val }
        "E2E_TEST_USER_PASSWORD"     { $env:TF_VAR_app_auth_password = $val }
        "E2E_TEST_USER_ID"           { $env:TF_VAR_app_auth_user_id = $val }
        "E2E_TEST_USER_NAME"         { $env:TF_VAR_app_auth_name = $val }
        "APP_AUTH_EMAIL"             { $env:TF_VAR_app_auth_email = $val }
        "APP_AUTH_PASSWORD"          { $env:TF_VAR_app_auth_password = $val }
        "APP_AUTH_USER_ID"           { $env:TF_VAR_app_auth_user_id = $val }
        "APP_AUTH_NAME"              { $env:TF_VAR_app_auth_name = $val }
        "AUTH_JWK_URI"               { $env:TF_VAR_auth_jwk_uri = $val }
        "CLOUDFRONT_ORIGIN_SECRET"   { $env:TF_VAR_cloudfront_origin_secret = $val }
        "REDIS_URL"                  { $env:TF_VAR_redis_url = $val }
        "KAFKA_BOOTSTRAP_SERVERS"    { $env:TF_VAR_kafka_bootstrap_servers = $val }
        "KAFKA_SASL_USERNAME"        { $env:TF_VAR_kafka_sasl_username = $val }
        "KAFKA_SASL_PASSWORD"        { $env:TF_VAR_kafka_sasl_password = $val }
        "RDS_MASTER_USERNAME"        { $env:TF_VAR_db_username = $val }
        "RDS_MASTER_PASSWORD"        { $env:TF_VAR_db_password = $val }
    }
}

$env:TF_VAR_api_gateway_image_uri   = "844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-api-gateway:latest"
$env:TF_VAR_portfolio_image_uri     = "844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-portfolio-service:latest"
$env:TF_VAR_market_data_image_uri   = "844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-market-data-service:latest"
$env:TF_VAR_insight_image_uri       = "844479804897.dkr.ecr.ap-south-1.amazonaws.com/wealth-insight-service:latest"

Write-Host "=== Applying Terraform plan ===" -ForegroundColor Cyan
& terraform apply -input=false tfplan

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=== Apply succeeded! ===" -ForegroundColor Green
    Write-Host "Lambda Function URLs:" -ForegroundColor Cyan
    & terraform output
} else {
    Write-Host "Apply failed — review errors above." -ForegroundColor Red
    exit 1
}
