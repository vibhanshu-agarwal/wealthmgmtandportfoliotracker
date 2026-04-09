output "api_gateway_function_url" {
  description = "HTTPS Function URL for api-gateway Lambda"
  value       = aws_lambda_function_url.api_gateway.function_url
}

output "portfolio_function_url" {
  description = "HTTPS Function URL for portfolio-service Lambda"
  value       = aws_lambda_function_url.portfolio.function_url
}

output "market_data_function_url" {
  description = "HTTPS Function URL for market-data-service Lambda"
  value       = aws_lambda_function_url.market_data.function_url
}

output "insight_function_url" {
  description = "HTTPS Function URL for insight-service Lambda"
  value       = aws_lambda_function_url.insight.function_url
}
