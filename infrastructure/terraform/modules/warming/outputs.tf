# =============================================================================
# Warming Module Outputs
# =============================================================================

output "rule_arns" {
  description = "Map of target key → EventBridge Rule ARN for all warming rules"
  value       = { for k, v in aws_cloudwatch_event_rule.targets : k => v.arn }
}

output "scheduler_role_arn" {
  description = "ARN of the IAM role used by EventBridge Rules to invoke API Destinations"
  value       = aws_iam_role.scheduler.arn
}

output "sns_topic_arn" {
  description = "ARN of the SNS topic that receives ConcurrentExecutions alarm notifications"
  value       = aws_sns_topic.concurrency_alarm.arn
}

output "cloudwatch_alarm_name" {
  description = "Name of the CloudWatch alarm monitoring account-level Lambda ConcurrentExecutions"
  value       = aws_cloudwatch_metric_alarm.concurrent_executions.alarm_name
}
