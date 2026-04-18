# =============================================================================
# Database Module — RDS PostgreSQL + ElastiCache Redis
#
# When is_local_dev = true or enable_aws_managed_database = false, AWS RDS and
# ElastiCache are skipped (count = 0). Outputs use local placeholders or empty
# strings so Neon/Atlas + external cache can replace paid managed AWS datastores.
# =============================================================================

locals {
  common_tags = {
    Project = var.project_name
    Module  = "database"
  }
  # Paid RDS/ElastiCache only when explicitly enabled and not LocalStack/local dev.
  use_managed_aws_db = !var.is_local_dev && var.enable_aws_managed_database
}

# ---------------------------------------------------------------------------
# RDS PostgreSQL — skipped for LocalStack/local dev or when managed DB disabled
# ---------------------------------------------------------------------------

resource "aws_db_instance" "portfolio" {
  count = local.use_managed_aws_db ? 1 : 0

  identifier        = "${var.project_name}-portfolio-db"
  engine            = "postgres"
  engine_version    = "16"
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage

  db_name  = "portfolio_db"
  username = var.db_username
  password = var.db_password

  skip_final_snapshot = true

  tags = local.common_tags
}

# ---------------------------------------------------------------------------
# ElastiCache Redis — skipped for LocalStack/local dev or when managed DB disabled
# ---------------------------------------------------------------------------

resource "aws_elasticache_cluster" "gateway_cache" {
  count = local.use_managed_aws_db ? 1 : 0

  cluster_id      = "${var.project_name}-gateway-cache"
  engine          = "redis"
  engine_version  = "7.0"
  node_type       = var.elasticache_node_type
  num_cache_nodes = 1
  port            = 6379

  tags = local.common_tags
}
