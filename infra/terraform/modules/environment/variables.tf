variable "name" {
  description = "Environment name, which is also the Spring profile: uat, or later prod."
  type        = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.name))
    error_message = "Lower-case letters, digits and hyphens, 2 to 16 characters."
  }
}

variable "domain_name" {
  description = "The address people use, such as uat.example.com. The certificate and DNS record are made for it."
  type        = string
}

variable "route53_zone_name" {
  description = "The Route 53 hosted zone that domain_name sits in, such as example.com."
  type        = string
}

variable "alert_email" {
  description = "Receives alarms. AWS emails it once to confirm the subscription."
  type        = string
}

variable "mail_from" {
  description = "Sender of every email, which must be a verified sender in SendGrid, e.g. 'ProductionOS <no-reply@example.com>'."
  type        = string
}

variable "backend_image" {
  description = "Backend image, repository URL and tag (the git commit)."
  type        = string
}

variable "frontend_image" {
  description = "Web app image, repository URL and tag (the git commit)."
  type        = string
}

# Network

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "How many zones to spread across. Two survive the loss of one."
  type        = number
  default     = 2
  validation {
    condition     = var.availability_zones >= 2 && var.availability_zones <= 3
    error_message = "Two or three."
  }
}

variable "nat_gateways" {
  description = "One is cheaper, but outbound traffic (email, payments) stops if its zone fails. Production: one per zone."
  type        = number
  default     = 1
}

# Application

variable "backend_cpu" {
  type    = number
  default = 1024
}

variable "backend_memory" {
  type    = number
  default = 2048
}

variable "frontend_cpu" {
  type    = number
  default = 512
}

variable "frontend_memory" {
  type    = number
  default = 1024
}

variable "min_instances" {
  description = "Instances of each service. Two, so one can fail or be replaced while the other serves."
  type        = number
  default     = 2
  validation {
    condition     = var.min_instances >= 2
    error_message = "At least two, or a single failure takes the service down."
  }
}

variable "max_instances" {
  description = "Upper bound when CPU stays high. Each backend instance holds db_pool_size database connections."
  type        = number
  default     = 4
}

variable "db_pool_size" {
  description = "Database connections per backend instance. max_instances times this must stay under the database's limit."
  type        = number
  default     = 10
}

variable "app_environment" {
  description = "Further plain settings for the backend, such as BILLING_SELLER_NAME. Never secrets."
  type        = map(string)
  default     = {}
}

# Database

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_engine_version" {
  description = "Major version; minor upgrades apply in the maintenance window."
  type        = string
  default     = "18"
}

variable "db_storage_gb" {
  type    = number
  default = 50
}

variable "db_max_storage_gb" {
  description = "Storage grows by itself up to this."
  type        = number
  default     = 200
}

variable "db_multi_az" {
  description = "A standby in another zone takes over within a couple of minutes if the primary fails."
  type        = bool
  default     = true
}

variable "db_backup_days" {
  description = "Automated backups, restorable to any second inside this many days."
  type        = number
  default     = 14
}

variable "deletion_protection" {
  description = "Stops Terraform or anyone else deleting the database and load balancer by mistake."
  type        = bool
  default     = true
}

# Operations

variable "log_retention_days" {
  description = "Application, firewall, flow and access logs. A year, so an incident found late can still be traced."
  type        = number
  default     = 365
}

variable "waf_requests_per_5_minutes" {
  description = "Requests one address may make in 5 minutes before the firewall blocks it. A factory may share one address."
  type        = number
  default     = 3000
}

variable "enable_exec_command" {
  description = "ECS Exec: a shell inside running containers for anyone with the IAM permission. For UAT diagnosis; keep off in production."
  type        = bool
  default     = false
}
