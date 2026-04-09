# =============================================================================
# Networking Module — CloudFront Distribution + Optional Route 53 Record
# =============================================================================

locals {
  # Strip "https://" prefix from the Function URL to get the origin domain
  origin_domain = replace(var.origin_url, "https://", "")
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_100"
  comment             = "Wealth Management & Portfolio Tracker"
  default_root_object = ""

  # Custom domain aliases (only when domain_name is provided)
  aliases = var.domain_name != "" ? [var.domain_name] : []

  origin {
    domain_name = local.origin_domain
    origin_id   = "api-gateway-lambda"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    # LURL Security: CloudFront injects this header on every request to the origin.
    # The api-gateway Spring Security filter validates this header and rejects
    # any request that doesn't carry the correct value (prevents direct Function URL access).
    custom_header {
      name  = "X-Origin-Verify"
      value = var.cloudfront_origin_secret
    }
  }

  default_cache_behavior {
    target_origin_id       = "api-gateway-lambda"
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods  = ["GET", "HEAD"]

    forwarded_values {
      query_string = true
      headers      = ["Authorization", "Content-Type", "Accept", "Origin"]

      cookies {
        forward = "all"
      }
    }

    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0
    compress    = true
  }

  # HTTPS certificate configuration
  viewer_certificate {
    cloudfront_default_certificate = var.domain_name == "" ? true : false
    acm_certificate_arn            = var.domain_name != "" ? var.acm_certificate_arn : null
    ssl_support_method             = var.domain_name != "" ? "sni-only" : null
    minimum_protocol_version       = var.domain_name != "" ? "TLSv1.2_2021" : null
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  tags = {
    Project = "wealth-management"
    Module  = "networking"
  }
}

# ---------------------------------------------------------------------------
# Optional Route 53 A alias record (only when domain_name is provided)
# ---------------------------------------------------------------------------

resource "aws_route53_record" "main" {
  count = var.domain_name != "" ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}
