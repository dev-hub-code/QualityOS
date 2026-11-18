# Layer 2 of the scaling notes: several instances of each service behind a load balancer that skips unhealthy ones.
# They are stateless: sessions are signed tokens, files are in S3, sign-in limits and job leases are in the database.

resource "aws_ecs_cluster" "main" {
  name = local.prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "service" {
  #checkov:skip=CKV_AWS_158:Encrypted by CloudWatch with its own key; a customer-managed key is a production decision
  for_each          = toset(["backend", "frontend", "db-admin"])
  name              = "/qualityos/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${var.name}.qualityos.internal"
  vpc  = aws_vpc.main.id
}

# The web app finds backend instances by DNS. ECS adds an instance once it is healthy and removes it before stopping it.
resource "aws_service_discovery_service" "backend" {
  name = "backend"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

locals {
  backend_environment = merge(var.app_environment, {
    SPRING_PROFILES_ACTIVE = var.name
    SERVER_PORT            = "8080"
    DB_URL                 = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${local.db_name}?sslmode=require"
    DB_APP_USER            = local.db_app_user
    DB_MIGRATION_USER      = local.db_migrator_user
    DB_POOL_SIZE           = tostring(var.db_pool_size)
    STORAGE_PROVIDER       = "s3"
    STORAGE_S3_BUCKET      = aws_s3_bucket.files.bucket
    STORAGE_S3_REGION      = data.aws_region.current.region
    # Unused with S3, but the setting must resolve.
    STORAGE_LOCAL_ROOT = "/tmp/qualityos-files"
    APP_URL            = "https://${var.domain_name}"
    MAIL_FROM          = var.mail_from
  })

  backend_secrets = concat(local.generated_secrets, local.provider_secrets)

  backend_container = {
    name         = "backend"
    image        = var.backend_image
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp", name = "http" }]
    environment  = [for key in sort(keys(local.backend_environment)) : { name = key, value = local.backend_environment[key] }]
    secrets = [for key in local.backend_secrets : {
      name      = key
      valueFrom = "${aws_secretsmanager_secret.app.arn}:${key}::"
    }]
    healthCheck = {
      command  = ["CMD-SHELL", "curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1"]
      interval = 10
      timeout  = 5
      retries  = 3
      # Migrations run before the first instance is ready.
      startPeriod = 120
    }
    # Spring finishes requests in progress for up to 20 seconds before it stops.
    stopTimeout = 30
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["backend"].name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "backend"
      }
    }
  }

  frontend_container = {
    name         = "frontend"
    image        = var.frontend_image
    essential    = true
    portMappings = [{ containerPort = 3000, protocol = "tcp", name = "http" }]
    environment = [
      { name = "BACKEND_URL", value = "http://${local.backend_host}:8080" },
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:3000/api/health || exit 1"]
      interval    = 10
      timeout     = 3
      retries     = 3
      startPeriod = 20
    }
    stopTimeout = 30
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["frontend"].name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "frontend"
      }
    }
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.prefix}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.backend.arn
  container_definitions    = jsonencode([local.backend_container])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
}

resource "aws_ecs_task_definition" "frontend" {
  family                   = "${local.prefix}-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.frontend_cpu
  memory                   = var.frontend_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.frontend.arn
  container_definitions    = jsonencode([local.frontend_container])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
}

resource "aws_ecs_service" "backend" {
  name                   = "backend"
  cluster                = aws_ecs_cluster.main.id
  task_definition        = aws_ecs_task_definition.backend.arn
  desired_count          = var.min_instances
  launch_type            = "FARGATE"
  enable_execute_command = var.enable_exec_command
  propagate_tags         = "SERVICE"

  # A new version starts beside the old one; the old one stops only once the new one is healthy.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  availability_zone_rebalancing = "ENABLED"

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.backend.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.backend.arn
  }

  lifecycle {
    # Autoscaling owns the count after the first apply.
    ignore_changes = [desired_count]
  }
}

resource "aws_ecs_service" "frontend" {
  name                   = "frontend"
  cluster                = aws_ecs_cluster.main.id
  task_definition        = aws_ecs_task_definition.frontend.arn
  desired_count          = var.min_instances
  launch_type            = "FARGATE"
  enable_execute_command = var.enable_exec_command
  propagate_tags         = "SERVICE"

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  availability_zone_rebalancing     = "ENABLED"
  health_check_grace_period_seconds = 60

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.frontend.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 3000
  }

  depends_on = [aws_lb_listener.https]

  lifecycle {
    ignore_changes = [desired_count]
  }
}

# More instances while CPU stays high, back to min_instances when it falls.

resource "aws_appautoscaling_target" "service" {
  for_each           = { backend = aws_ecs_service.backend.name, frontend = aws_ecs_service.frontend.name }
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${each.value}"
  min_capacity       = var.min_instances
  max_capacity       = var.max_instances
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = aws_appautoscaling_target.service
  name               = "${local.prefix}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = each.value.service_namespace
  scalable_dimension = each.value.scalable_dimension
  resource_id        = each.value.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_out_cooldown = 120
    scale_in_cooldown  = 600
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Creates the application's database roles, as docker/postgres/init does locally. Run by infra/scripts/db-setup.sh
# once, and again after the database passwords change. Safe to repeat.

locals {
  db_setup_script = <<-SH
    set -eu
    psql -v ON_ERROR_STOP=1 -v migrator="$MIGRATOR_USER" -v migrator_password="$MIGRATOR_PASSWORD" \
      -v app="$APP_USER" -v app_password="$APP_PASSWORD" -v database="$PGDATABASE" <<'SQL'
    SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', :'migrator')
      WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'migrator') \gexec
    SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', :'app')
      WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app') \gexec
    ALTER ROLE :"migrator" WITH LOGIN PASSWORD :'migrator_password';
    ALTER ROLE :"app" WITH LOGIN PASSWORD :'app_password';
    -- The admin must be a member to hand the database over.
    GRANT :"migrator" TO CURRENT_USER;
    ALTER DATABASE :"database" OWNER TO :"migrator";
    SELECT rolname, rolbypassrls, rolsuper FROM pg_roles WHERE rolname IN (:'migrator', :'app');
    SQL
    echo "Database roles are ready."
  SH
}

resource "aws_ecs_task_definition" "db_admin" {
  family                   = "${local.prefix}-db-admin"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.db_admin_execution.arn

  container_definitions = jsonencode([{
    name      = "db-admin"
    image     = "public.ecr.aws/docker/library/postgres:${var.db_engine_version}-alpine"
    essential = true
    command   = ["sh", "-c", local.db_setup_script]
    environment = [
      { name = "PGHOST", value = aws_db_instance.main.address },
      { name = "PGPORT", value = tostring(aws_db_instance.main.port) },
      { name = "PGDATABASE", value = local.db_name },
      { name = "PGSSLMODE", value = "require" },
      { name = "MIGRATOR_USER", value = local.db_migrator_user },
      { name = "APP_USER", value = local.db_app_user },
    ]
    secrets = [
      { name = "PGUSER", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:username::" },
      { name = "PGPASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
      { name = "MIGRATOR_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:DB_MIGRATION_PASSWORD::" },
      { name = "APP_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:DB_APP_PASSWORD::" },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["db-admin"].name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "db-admin"
      }
    }
  }])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
}
