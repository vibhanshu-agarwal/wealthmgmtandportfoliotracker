# =============================================================================
# Warming Module — EventBridge Rules + API Destinations
#
# Keeps one execution environment warm per Lambda function at the configured
# cadence (default: every 5 minutes) by making real HTTPS GET requests to
# each service's /actuator/health endpoint. This avoids the 15–40 s cold-start
# penalty while staying well within the AWS Free Tier.
#
# Topology (reconciled with P5/P6 pre-flight findings):
#   api-gateway    → CloudFront URL  (CloudFrontOriginVerifyFilter requires CF path)
#   portfolio      → direct FURL     (no origin-verify filter on Function URL)
#   market-data    → direct FURL     (same; no gateway-routable health endpoint)
#   insight        → direct FURL     (same)
#
# Cost: 4 targets × 12/hr × 24 × 30 = 34,560 invocations/month.
#       EventBridge: first 14M/month free. Lambda warm hits: ~$0 (Free Tier).
#
# Note: EventBridge *Rules* (aws_cloudwatch_event_rule) are used instead of
#       EventBridge Scheduler (aws_scheduler_schedule) because Scheduler does
#       NOT accept arn:aws:events:... API Destination ARNs as targets. Rules
#       natively support API Destinations via aws_cloudwatch_event_target.
#
# Rollback: flip enable_warming = false in tfvars and run terraform apply.
#           Rules disabled/deleted within ~1 minute. Zero data loss.
# =============================================================================

# ---------------------------------------------------------------------------
# EventBridge Connection — required by API Destinations even for public endpoints.
# authorization_type = "API_KEY" with a benign dummy value is the lightest-weight
# option; the injected header (X-Warmer-Source) is ignored by Spring Boot.
# There is no "NONE" auth type in the EventBridge Connections API.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_event_connection" "warming" {
  name               = "wealth-warming-public"
  description        = "Dummy-key connection for warming public Lambda health endpoints (header is ignored by Spring Boot)"
  authorization_type = "API_KEY"

  auth_parameters {
    api_key {
      key   = "X-Warmer-Source"
      value = "eventbridge-scheduler"
    }
  }
}

# ---------------------------------------------------------------------------
# API Destinations — one per warming target.
# Each destination binds a connection to a specific HTTPS endpoint + method.
# invocation_rate_limit_per_second = 1 is the minimum; warming fires every
# 5 min so this rate limit is never approached.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_event_api_destination" "targets" {
  for_each = var.targets

  name                             = "wealth-warm-${each.key}"
  description                      = "Warming destination for ${each.key} Lambda (/actuator/health)"
  connection_arn                   = aws_cloudwatch_event_connection.warming.arn
  invocation_endpoint              = each.value.url
  http_method                      = each.value.method
  invocation_rate_limit_per_second = 1
}

# ---------------------------------------------------------------------------
# EventBridge Rules — one scheduled rule per warming target.
# EventBridge Rules natively support API Destinations as targets (unlike
# EventBridge Scheduler which cannot accept arn:aws:events:... ARNs).
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_event_rule" "targets" {
  for_each = var.targets

  name                = "wealth-warm-${each.key}"
  description         = "Keep ${each.key} Lambda warm: GET ${each.value.url} every ${var.schedule_cron}"
  schedule_expression = var.schedule_cron
  state               = "ENABLED"
}

# ---------------------------------------------------------------------------
# EventBridge Targets — binds each rule to its API Destination.
# The full API Destination ARN (including UUID suffix from Terraform) is
# accepted here; EventBridge Rules handle it correctly unlike Scheduler.
# retry_policy: fire-and-forget — next tick 5 min later will re-warm if needed.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_event_target" "targets" {
  for_each = var.targets

  rule     = aws_cloudwatch_event_rule.targets[each.key].name
  arn      = aws_cloudwatch_event_api_destination.targets[each.key].arn
  role_arn = aws_iam_role.scheduler.arn
  input    = "{}" # GET request; body is ignored by Spring Boot actuator

  retry_policy {
    maximum_event_age_in_seconds = 60 # discard stale warming ticks immediately
    maximum_retry_attempts       = 0  # warming is idempotent — next tick will re-warm
  }
}


# ---------------------------------------------------------------------------
# IAM Role for EventBridge Rules → API Destinations
# Trust policy scoped to events.amazonaws.com (EventBridge Rules service).
# aws:SourceAccount condition prevents confused-deputy attacks.
# ---------------------------------------------------------------------------
resource "aws_iam_role" "scheduler" {
  name        = "wealth-lambda-warming-scheduler"
  description = "Allows EventBridge Rules to invoke API Destinations for Lambda warming"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = var.aws_account_id
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "scheduler_invoke_destinations" {
  name = "invoke-warming-api-destinations"
  role = aws_iam_role.scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "InvokeWarmingDestinations"
      Effect   = "Allow"
      Action   = "events:InvokeApiDestination"
      Resource = [for d in aws_cloudwatch_event_api_destination.targets : d.arn]
    }]
  })
}

# ---------------------------------------------------------------------------
# SNS Topic + Email Subscription — ConcurrentExecutions alarm notification.
# AWS sends a confirmation email to var.alarm_email immediately after apply;
# you must click "Confirm subscription" before alerts will be delivered.
# ---------------------------------------------------------------------------
resource "aws_sns_topic" "concurrency_alarm" {
  name = "wealth-lambda-concurrency-alarm"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.concurrency_alarm.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---------------------------------------------------------------------------
# CloudWatch Alarm — account-level ConcurrentExecutions guardrail.
# No Dimensions block = account-wide aggregate across all Lambda functions.
# Threshold default 8 leaves a 2-unit buffer below the 10-unit ap-south-1 quota.
# Fires on 2 consecutive 60-second periods to avoid transient spikes triggering alerts.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "concurrent_executions" {
  alarm_name          = "wealth-lambda-concurrent-executions-high"
  alarm_description   = "Lambda ConcurrentExecutions >= ${var.concurrent_executions_threshold} — approaching the ap-south-1 account hard limit of 10. Check for traffic spikes or quota exhaustion."
  namespace           = "AWS/Lambda"
  metric_name         = "ConcurrentExecutions"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = var.concurrent_executions_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.concurrency_alarm.arn]
  ok_actions    = [aws_sns_topic.concurrency_alarm.arn]
}
