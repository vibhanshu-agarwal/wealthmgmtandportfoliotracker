# =============================================================================
# CDN module — CloudFront distribution + static route rewrite (viewer-request)
# =============================================================================

locals {
  # Strip "https://" prefix and trailing slash from the Function URL to get the API origin domain.
  api_origin_domain = trimsuffix(replace(var.origin_url, "https://", ""), "/")

  # S3 static origin only (Next.js prefetch uses HEAD; OPTIONS for CORS preflight).
  # API traffic uses ordered_cache_behavior /api/* with full method set — do not reuse this there.
  frontend_allowed_cached_methods = ["GET", "HEAD", "OPTIONS"]
}

resource "aws_cloudfront_origin_access_control" "static_s3" {
  name                              = "wealth-static-s3-oac"
  description                       = "OAC for static frontend S3 origin"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Viewer-request on the **default** (S3 static) cache behavior only — never runs for /api/*.
resource "aws_cloudfront_function" "static_route_rewrite" {
  name    = "wealth-static-route-rewrite"
  runtime = "cloudfront-js-1.0"
  comment = "Rewrite extensionless frontend routes to exported HTML objects (viewer-request)"
  publish = true
  code    = <<-EOF
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  // Keep API, Next assets, and well-known paths untouched.
  if (uri.startsWith('/api/') || uri.startsWith('/_next/') || uri.startsWith('/.well-known/')) {
    return request;
  }

  // Map root to exported homepage.
  if (uri === '/') {
    request.uri = '/index.html';
    return request;
  }

  // Leave explicit-extension paths untouched (e.g. .html, .js, .ico, .txt).
  if (uri.match(/\/[^\/]+\.[^\/]+$/)) {
    return request;
  }

  // Trailing slash → index under that prefix (/foo/ → /foo/index.html).
  if (uri.endsWith('/')) {
    request.uri = uri + 'index.html';
    return request;
  }

  // Static export: extensionless paths map to sibling .html objects (/foo → /foo.html).
  request.uri = uri + '.html';
  return request;
}
EOF
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
    domain_name              = var.static_site_bucket_regional_domain_name
    origin_id                = "frontend-static-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.static_s3.id

    s3_origin_config {
      origin_access_identity = ""
    }
  }

  origin {
    domain_name = local.api_origin_domain
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
    target_origin_id       = "frontend-static-s3"
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = local.frontend_allowed_cached_methods
    cached_methods  = local.frontend_allowed_cached_methods

    forwarded_values {
      query_string = false
      headers      = ["Origin"]

      cookies {
        forward = "none"
      }
    }

    min_ttl     = 0
    default_ttl = 3600
    max_ttl     = 86400
    compress    = true

    # Active viewer-request rewrite: extensionless paths → .html; trailing slash → …/index.html.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.static_route_rewrite.arn
    }
  }

  ordered_cache_behavior {
    path_pattern           = "/api/*"
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
    Module  = "cdn"
  }
}

# ---------------------------------------------------------------------------
# Optional Route 53 A alias record (only when both domain_name and route53_zone_id are provided).
# When DNS is managed externally (e.g. domain registrar), leave route53_zone_id empty.
# ---------------------------------------------------------------------------

resource "aws_route53_record" "main" {
  count = var.domain_name != "" && var.route53_zone_id != "" ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}
