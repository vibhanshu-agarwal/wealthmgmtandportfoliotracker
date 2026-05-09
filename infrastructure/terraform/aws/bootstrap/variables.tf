variable "state_bucket_name" {
  type        = string
  description = "Name of the S3 bucket for Terraform remote state"
}

variable "lock_table_name" {
  type        = string
  description = "Name of the DynamoDB table for state locking"
}

variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region"
}
