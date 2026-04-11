output "rds_endpoint" {
  description = "RDS PostgreSQL connection endpoint (host:port)"
  value       = var.is_local_dev ? "${var.local_postgres_host}:${var.local_postgres_port}" : aws_db_instance.portfolio[0].endpoint
}

output "rds_address" {
  description = "RDS PostgreSQL hostname (without port)"
  value       = var.is_local_dev ? var.local_postgres_host : aws_db_instance.portfolio[0].address
}

output "rds_port" {
  description = "RDS PostgreSQL port"
  value       = var.is_local_dev ? var.local_postgres_port : aws_db_instance.portfolio[0].port
}

output "rds_db_name" {
  description = "RDS database name"
  value       = var.is_local_dev ? "portfolio_db" : aws_db_instance.portfolio[0].db_name
}

output "elasticache_endpoint" {
  description = "ElastiCache Redis cache node endpoint"
  value       = var.is_local_dev ? var.local_redis_host : aws_elasticache_cluster.gateway_cache[0].cache_nodes[0].address
}

output "elasticache_port" {
  description = "ElastiCache Redis port"
  value       = var.is_local_dev ? var.local_redis_port : aws_elasticache_cluster.gateway_cache[0].cache_nodes[0].port
}
