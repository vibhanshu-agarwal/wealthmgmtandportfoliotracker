output "cloudfront_domain_name" {
  description = "The CloudFront distribution domain name (public endpoint)"
  value       = aws_cloudfront_distribution.main.domain_name
}

output "cloudfront_distribution_id" {
  description = "The CloudFront distribution ID"
  value       = aws_cloudfront_distribution.main.id
}
