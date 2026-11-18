variable "domain_name" {
  description = "For example uat.example.com."
  type        = string
}

variable "route53_zone_name" {
  description = "For example example.com."
  type        = string
}

variable "alert_email" {
  type = string
}

variable "mail_from" {
  description = "A verified sender in SendGrid, e.g. 'ProductionOS UAT <no-reply@example.com>'."
  type        = string
}

variable "image_tag" {
  description = "The git commit to run. infra/scripts/deploy.sh sets it."
  type        = string
}

variable "app_environment" {
  description = "Plain backend settings, such as BILLING_SELLER_NAME. Never secrets."
  type        = map(string)
  default     = {}
}
