data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  prefix = "qualityos-${var.name}"
  zones  = slice(data.aws_availability_zones.available.names, 0, var.availability_zones)

  # Roles as in docker/postgres/init: the migrator owns the schema, the application role only reads and writes rows,
  # so row-level security applies to it.
  db_name          = "qualityos"
  db_app_user      = "qualityos_app"
  db_migrator_user = "qualityos_migrator"

  # Private DNS name the web app reaches the backend by. Nothing outside the VPC can reach it.
  backend_host = "backend.${var.name}.qualityos.internal"
}
