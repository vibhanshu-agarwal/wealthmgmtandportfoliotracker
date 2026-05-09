# =============================================================================
# ECR Repositories — Container image registries for each Lambda service
# =============================================================================
# api-gateway ECR repository is managed separately (pre-existing).
# These three repositories are created as part of the Phase 4 service split.

resource "aws_ecr_repository" "portfolio" {
  name                 = "wealth-portfolio-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  tags = {
    Project = "wealth-management"
    Service = "portfolio-service"
  }
}

resource "aws_ecr_repository" "market_data" {
  name                 = "wealth-market-data-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  tags = {
    Project = "wealth-management"
    Service = "market-data-service"
  }
}

resource "aws_ecr_repository" "insight" {
  name                 = "wealth-insight-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  tags = {
    Project = "wealth-management"
    Service = "insight-service"
  }
}

# ---------------------------------------------------------------------------
# Outputs — expose repository URLs for CI/CD reference
# ---------------------------------------------------------------------------

output "portfolio_ecr_repository_url" {
  description = "ECR repository URL for wealth-portfolio-service"
  value       = aws_ecr_repository.portfolio.repository_url
}

output "market_data_ecr_repository_url" {
  description = "ECR repository URL for wealth-market-data-service"
  value       = aws_ecr_repository.market_data.repository_url
}

output "insight_ecr_repository_url" {
  description = "ECR repository URL for wealth-insight-service"
  value       = aws_ecr_repository.insight.repository_url
}
