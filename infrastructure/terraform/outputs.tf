output "cloudfront_domain_name" {
  description = "The public CloudFront endpoint URL for the application"
  value       = module.networking.cloudfront_domain_name
}

output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID for cache invalidation"
  value       = module.networking.cloudfront_distribution_id
}

output "rds_endpoint" {
  description = "RDS PostgreSQL connection endpoint (host:port)"
  value       = module.database.rds_endpoint
}

output "elasticache_endpoint" {
  description = "ElastiCache Redis connection endpoint"
  value       = module.database.elasticache_endpoint
}
