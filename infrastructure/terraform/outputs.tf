output "website_url" {
  description = "HTTPS website URL served by CloudFront."
  value       = "https://${aws_cloudfront_distribution.frontend.domain_name}"
}

output "api_url" {
  description = "HTTPS API Gateway endpoint."
  value       = aws_apigatewayv2_api.booking.api_endpoint
}

output "dynamodb_table_name" {
  description = "DynamoDB table storing bookings and room-night reservations."
  value       = aws_dynamodb_table.bookings.name
}

output "cognito_user_pool_id" {
  description = "Cognito user pool used for guest authentication."
  value       = aws_cognito_user_pool.guests.id
}
