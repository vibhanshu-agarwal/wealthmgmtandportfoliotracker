# =============================================================================
# Database Module — RDS PostgreSQL + ElastiCache Redis
#
# When is_local_dev = true, AWS resources are skipped (count = 0) and outputs
# return hardcoded Docker container endpoints. This "Seamless Output Bypass"
# allows the Terraform graph to remain valid while the actual database layer
# is provided by vanilla Docker containers (docker-compose.yml).
# =============================================================================

locals {
  common_tags = {
    Project = var.project_name
    Module  = "database"
  }
}

# ---------------------------------------------------------------------------
# RDS PostgreSQL — skipped when is_local_dev = true
# ---------------------------------------------------------------------------

resource "aws_db_instance" "portfolio" {
  count = var.is_local_dev ? 0 : 1

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
# ElastiCache Redis — skipped when is_local_dev = true
# ---------------------------------------------------------------------------

resource "aws_elasticache_cluster" "gateway_cache" {
  count = var.is_local_dev ? 0 : 1

  cluster_id      = "${var.project_name}-gateway-cache"
  engine          = "redis"
  engine_version  = "7.0"
  node_type       = var.elasticache_node_type
  num_cache_nodes = 1
  port            = 6379

  tags = local.common_tags
}
