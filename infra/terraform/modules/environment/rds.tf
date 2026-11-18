# Layer 1 of the scaling notes: the database on its own server, sized and tuned separately from the application.
# A standby in a second zone (Multi-AZ) takes over if the primary or its zone fails.

resource "aws_db_subnet_group" "main" {
  name       = local.prefix
  subnet_ids = aws_subnet.db[*].id
}

resource "aws_db_parameter_group" "main" {
  name   = "${local.prefix}-postgres${var.db_engine_version}"
  family = "postgres${var.db_engine_version}"

  parameter {
    # Every connection encrypted.
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    # Queries slower than a second are logged, to find the next bottleneck before users do.
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "log_lock_waits"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  identifier     = local.prefix
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = local.db_name
  username = "qualityos_admin"
  # RDS creates and keeps the admin password in Secrets Manager; it never passes through Terraform.
  manage_master_user_password = true

  allocated_storage     = var.db_storage_gb
  max_allocated_storage = var.db_max_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = var.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.main.name

  backup_retention_period  = var.db_backup_days
  backup_window            = "20:30-21:30"         # 02:00-03:00 IST
  maintenance_window       = "sun:22:00-sun:23:00" # Sunday 03:30-04:30 IST
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  auto_minor_version_upgrade = true
  apply_immediately          = false

  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.prefix}-final"
}
