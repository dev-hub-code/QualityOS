output "url" {
  value = "https://${var.domain_name}"
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "services" {
  value = { backend = aws_ecs_service.backend.name, frontend = aws_ecs_service.frontend.name }
}

output "app_secret_arn" {
  description = "Filled by infra/scripts/seed-secrets.sh."
  value       = aws_secretsmanager_secret.app.arn
}

output "db_setup" {
  description = "What infra/scripts/db-setup.sh needs to run the database setup task."
  value = {
    task_definition = aws_ecs_task_definition.db_admin.family
    subnets         = aws_subnet.app[*].id
    security_group  = aws_security_group.db_admin.id
    log_group       = aws_cloudwatch_log_group.service["db-admin"].name
  }
}

output "files_bucket" {
  value = aws_s3_bucket.files.bucket
}

output "database" {
  value = aws_db_instance.main.identifier
}

output "log_groups" {
  value = { for name, group in aws_cloudwatch_log_group.service : name => group.name }
}
