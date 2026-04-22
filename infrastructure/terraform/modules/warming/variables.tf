# =============================================================================
# Warming Module Variables
# =============================================================================

variable "targets" {
  type = map(object({
    url    = string
    method = string
  }))
  description = <<-EOT
    Map of warming targets keyed by a short name (used in resource names).
    Each entry specifies the HTTPS endpoint to GET and the HTTP method.
    Example:
      api_gateway = { url = "https://d123.cloudfront.net/actuator/health", method = "GET" }
      portfolio   = { url = "https://abc.lambda-url.ap-south-1.on.aws/actuator/health", method = "GET" }
  EOT
}

variable "schedule_cron" {
  type        = string
  default     = "rate(5 minutes)"
  description = <<-EOT
    EventBridge Scheduler rate or cron expression applied to all warming schedules.
    Default "rate(5 minutes)" keeps one execution environment alive per function.
    Reduce to "rate(3 minutes)" only if CloudWatch shows Init Duration spikes
    between the 5-minute ticks, which would indicate the JVM is being evicted.
    Valid formats: "rate(N minutes|hours|days)" or "cron(...)".
  EOT
}

variable "aws_account_id" {
  type        = string
  description = <<-EOT
    AWS account ID. Used to scope the EventBridge Scheduler IAM trust policy with
    an aws:SourceAccount condition, preventing confused-deputy attacks.
  EOT
}

variable "alarm_email" {
  type        = string
  description = <<-EOT
    Email address that receives the SNS notification when account-level Lambda
    ConcurrentExecutions reaches the alarm threshold. AWS sends a confirmation
    email to this address after the first terraform apply — you must click
    "Confirm subscription" before alerts will be delivered.
  EOT
}

variable "concurrent_executions_threshold" {
  type        = number
  default     = 8
  description = <<-EOT
    CloudWatch alarm fires (and sends SNS email) when the account-level Lambda
    ConcurrentExecutions metric reaches this value. Default 8 provides a 2-unit
    buffer below the ap-south-1 hard limit of 10 unreserved concurrent executions.
    Lower this to 7 if provisioned concurrency is enabled on any function, as PC
    counts against the same pool.
  EOT
}
