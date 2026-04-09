output "cloudfront_domain_name" {
  description = "The CloudFront distribution domain name (public endpoint)"
  value       = aws_cloudfront_distribution.main.domain_name
}
