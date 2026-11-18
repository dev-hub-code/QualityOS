output "url" {
  value = module.environment.url
}

output "cluster" {
  value = module.environment.cluster
}

output "services" {
  value = module.environment.services
}

output "app_secret_arn" {
  value = module.environment.app_secret_arn
}

output "db_setup" {
  value = module.environment.db_setup
}

output "files_bucket" {
  value = module.environment.files_bucket
}

output "log_groups" {
  value = module.environment.log_groups
}
