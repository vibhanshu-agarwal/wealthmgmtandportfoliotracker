# Networking module variables — stub (populated in Task 6)

variable "origin_url" {
  type = string
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
