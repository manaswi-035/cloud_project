variable "aws_region" {
  description = "AWS region used for the API, Lambda, DynamoDB, and website."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix used for AWS resource names."
  type        = string
  default     = "hotel-booking"

  validation {
    condition     = can(regex("^[a-z0-9-]{3,30}$", var.project_name))
    error_message = "project_name must be 3-30 lowercase letters, digits, or hyphens."
  }
}
