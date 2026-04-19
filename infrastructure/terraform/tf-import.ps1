# tf-import.ps1 — Import existing AWS resources into Terraform state, then plan
# Run from repo root: pwsh infrastructure/terraform/tf-import.ps1

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot  # run from infrastructure/terraform/

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

$varFlags = @(
    "-var=state_bucket_name=vibhanshu-tf-state-2026",
    "-var=lock_table_name=vibhanshu-terraform-locks",
    "-var=artifact_bucket_name=wealth-artifacts-local",
    "-var=frontend_bucket_name=vibhanshu-s3-wealthmgmt-demo-bucket",
    "-var=s3_key_api_gateway=api-gateway/api-gateway.jar",
    "-var=enable_aws_managed_database=false"
)

function Clear-TfLock {
    # Check DynamoDB for any active lock and force-unlock it
    $lockId = aws dynamodb get-item `
        --table-name vibhanshu-terraform-locks `
        --key '{"LockID":{"S":"vibhanshu-tf-state-2026/terraform.tfstate"}}' `
        --query 'Item.Info.S' --output text --region ap-south-1 2>$null
    
    if ($lockId -and $lockId -ne "None" -and $lockId -ne "") {
        # Extract the ID field from the JSON info
        try {
            $info = $lockId | ConvertFrom-Json
            $id = $info.ID
            if ($id) {
                Write-Host "Force-unlocking stale lock: $id" -ForegroundColor Yellow
                & terraform force-unlock -force $id
            }
        } catch {
            # Try direct DynamoDB delete as fallback
            Write-Host "Clearing lock via DynamoDB delete..." -ForegroundColor Yellow
            aws dynamodb delete-item `
                --table-name vibhanshu-terraform-locks `
                --key '{"LockID":{"S":"vibhanshu-tf-state-2026/terraform.tfstate"}}' `
                --region ap-south-1 2>$null
        }
    }
}

Write-Host "=== Clearing any stale locks ===" -ForegroundColor Cyan
Clear-TfLock
Start-Sleep -Seconds 2

Write-Host "=== Importing ECR repositories ===" -ForegroundColor Cyan

# Check current state
$stateList = & terraform state list 2>&1
$portfolioInState   = $stateList -match "aws_ecr_repository.portfolio"
$marketDataInState  = $stateList -match "aws_ecr_repository.market_data"
$insightInState     = $stateList -match "aws_ecr_repository.insight"

if (-not $portfolioInState) {
    Write-Host "Importing aws_ecr_repository.portfolio..."
    & terraform import -lock=false @varFlags aws_ecr_repository.portfolio wealth-portfolio-service
    Start-Sleep -Seconds 1
} else {
    Write-Host "aws_ecr_repository.portfolio already in state — skipping" -ForegroundColor Green
}

if (-not $marketDataInState) {
    Write-Host "Importing aws_ecr_repository.market_data..."
    & terraform import -lock=false @varFlags aws_ecr_repository.market_data wealth-market-data-service
    Start-Sleep -Seconds 1
} else {
    Write-Host "aws_ecr_repository.market_data already in state — skipping" -ForegroundColor Green
}

if (-not $insightInState) {
    Write-Host "Importing aws_ecr_repository.insight..."
    & terraform import -lock=false @varFlags aws_ecr_repository.insight wealth-insight-service
    Start-Sleep -Seconds 1
} else {
    Write-Host "aws_ecr_repository.insight already in state — skipping" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Current state ===" -ForegroundColor Cyan
& terraform state list

Write-Host ""
Write-Host "=== Running terraform plan ===" -ForegroundColor Cyan
& terraform plan @varFlags -input=false -out=tfplan

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=== Plan succeeded! Review above, then apply with: ===" -ForegroundColor Green
    Write-Host "  pwsh infrastructure/terraform/tf-apply.ps1" -ForegroundColor Green
} else {
    Write-Host "Plan failed — review errors above." -ForegroundColor Red
    exit 1
}
