# CDN module — CloudFront distribution, OAC, static route rewrite function

variable "origin_url" {
  type = string
}

variable "static_site_bucket_regional_domain_name" {
  type        = string
  description = "Regional domain name for the frontend static S3 bucket"
}

variable "cloudfront_origin_secret" {
  type      = string
  sensitive = true
}

variable "domain_name" {
  type    = string
  default = ""
}

variable "acm_certificate_arn" {
  type    = string
  default = ""
}

variable "route53_zone_id" {
  type    = string
  default = ""
}
