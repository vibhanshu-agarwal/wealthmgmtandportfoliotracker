output "rds_endpoint" {
  description = "RDS PostgreSQL connection endpoint (host:port); empty when managed AWS DB is disabled"
  value = var.is_local_dev ? "${var.local_postgres_host}:${var.local_postgres_port}" : (
    local.use_managed_aws_db ? aws_db_instance.portfolio[0].endpoint : ""
  )
}

output "rds_address" {
  description = "RDS PostgreSQL hostname (without port); empty when managed AWS DB is disabled"
  value = var.is_local_dev ? var.local_postgres_host : (
    local.use_managed_aws_db ? aws_db_instance.portfolio[0].address : ""
  )
}

output "rds_port" {
  description = "RDS PostgreSQL port"
  value = var.is_local_dev ? var.local_postgres_port : (
    local.use_managed_aws_db ? aws_db_instance.portfolio[0].port : 0
  )
}

output "rds_db_name" {
  description = "RDS database name"
  value = var.is_local_dev ? "portfolio_db" : (
    local.use_managed_aws_db ? aws_db_instance.portfolio[0].db_name : ""
  )
}

output "elasticache_endpoint" {
  description = "ElastiCache Redis cache node address; empty when managed AWS DB is disabled"
  value = var.is_local_dev ? var.local_redis_host : (
    local.use_managed_aws_db ? aws_elasticache_cluster.gateway_cache[0].cache_nodes[0].address : ""
  )
}

output "elasticache_port" {
  description = "ElastiCache Redis port"
  value = var.is_local_dev ? var.local_redis_port : (
    local.use_managed_aws_db ? aws_elasticache_cluster.gateway_cache[0].cache_nodes[0].port : 0
  )
}
