# =============================================================================
# Warming Module Outputs
# =============================================================================

output "schedule_group_name" {
  description = "Name of the EventBridge Scheduler schedule group (wealth-lambda-warming)"
  value       = aws_scheduler_schedule_group.warming.name
}

output "schedule_arns" {
  description = "Map of target key → EventBridge schedule ARN for all warming schedules"
  value       = { for k, v in aws_scheduler_schedule.targets : k => v.arn }
}

output "scheduler_role_arn" {
  description = "ARN of the IAM role used by EventBridge Scheduler to invoke API Destinations"
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
