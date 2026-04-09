output "cloudfront_domain_name" {
  description = "The public CloudFront endpoint URL for the application"
  value       = module.networking.cloudfront_domain_name
}
