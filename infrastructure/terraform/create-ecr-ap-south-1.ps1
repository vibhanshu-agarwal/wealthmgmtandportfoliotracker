# create-ecr-ap-south-1.ps1 — Create ECR repos in ap-south-1
# Run from repo root: pwsh infrastructure/terraform/create-ecr-ap-south-1.ps1

Set-Location $PSScriptRoot

$envFile = Join-Path $PSScriptRoot "..\..\\.env.secrets"
$secrets = Get-Content $envFile | Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' }
foreach ($line in $secrets) {
    $parts = $line -split '=', 2; $key = $parts[0].Trim(); $val = if ($parts.Length -gt 1) { $parts[1].Trim() } else { "" }
    switch ($key) {
        "POSTGRES_CONNECTION_STRING" { $env:TF_VAR_postgres_connection_string = $val }
        "MONGODB_CONNECTION_STRING"  { $env:TF_VAR_mongodb_connection_string = $val }
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

Write-Host "=== Creating ECR repos in ap-south-1 ===" -ForegroundColor Cyan

& terraform apply `
    "-var=state_bucket_name=vibhanshu-tf-state-2026" `
    "-var=lock_table_name=vibhanshu-terraform-locks" `
    "-var=artifact_bucket_name=wealth-artifacts-local" `
    "-var=frontend_bucket_name=vibhanshu-s3-wealthmgmt-demo-bucket" `
    "-var=aws_region=ap-south-1" `
    "-var=enable_aws_managed_database=false" `
    "-input=false" `
    "-auto-approve" `
    "-target=aws_ecr_repository.portfolio" `
    "-target=aws_ecr_repository.market_data" `
    "-target=aws_ecr_repository.insight"

Write-Host "Exit: $LASTEXITCODE"
