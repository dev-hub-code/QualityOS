# Layer 8 of the scaling notes: alarms on what users would feel (errors, slowness, outage) and on saturation, so a
# problem reaches us before it reaches them. Each alarm emails alert_email, and emails again when it clears.

resource "aws_sns_topic" "alerts" {
  name = "${local.prefix}-alerts"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = [aws_sns_topic.alerts.arn]
  alb_dimension = { LoadBalancer = aws_lb.main.arn_suffix }
  tg_dimension  = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.frontend.arn_suffix }
}

# Symptoms

resource "aws_cloudwatch_metric_alarm" "site_down" {
  alarm_name          = "${local.prefix} site down: no healthy web instance"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = local.tg_dimension
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "server_errors" {
  alarm_name          = "${local.prefix} server errors: over 2% of requests failing"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 2
  evaluation_periods  = 3
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "rate"
    expression  = "IF(requests > 50, 100 * (FILL(elb, 0) + FILL(target, 0)) / requests, 0)"
    label       = "Percent of requests answered 5xx"
    return_data = true
  }
  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      dimensions  = local.alb_dimension
      stat        = "Sum"
      period      = 300
    }
  }
  metric_query {
    id = "elb"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      dimensions  = local.alb_dimension
      stat        = "Sum"
      period      = 300
    }
  }
  metric_query {
    id = "target"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = local.alb_dimension
      stat        = "Sum"
      period      = 300
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "slow" {
  alarm_name          = "${local.prefix} slow: 95th percentile response over 2 seconds"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = local.tg_dimension
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_log_metric_filter" "backend_errors" {
  name           = "${local.prefix}-backend-errors"
  log_group_name = aws_cloudwatch_log_group.service["backend"].name
  # The backend logs ECS JSON in UAT and production.
  pattern = "{ $.log.level = \"ERROR\" }"

  metric_transformation {
    name          = "BackendErrors"
    namespace     = "QualityOS/${var.name}"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "backend_errors" {
  alarm_name          = "${local.prefix} backend logging errors"
  namespace           = "QualityOS/${var.name}"
  metric_name         = "BackendErrors"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Capacity: fewer instances than planned means one more failure is an outage.

resource "aws_cloudwatch_metric_alarm" "instances_missing" {
  for_each            = { backend = aws_ecs_service.backend.name, frontend = aws_ecs_service.frontend.name }
  alarm_name          = "${local.prefix} ${each.key}: fewer than ${var.min_instances} instances running"
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 5
  comparison_operator = "LessThanThreshold"
  threshold           = var.min_instances
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "service_memory" {
  for_each            = { backend = aws_ecs_service.backend.name, frontend = aws_ecs_service.frontend.name }
  alarm_name          = "${local.prefix} ${each.key}: memory over 85%"
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 85
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Database saturation: the signal to look at the next layer (cache, then read replicas) before adding it.

resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  alarm_name          = "${local.prefix} database CPU over 80%"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_storage" {
  alarm_name          = "${local.prefix} database storage under 10 GB free"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 10 * 1024 * 1024 * 1024
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_memory" {
  alarm_name          = "${local.prefix} database memory under 256 MB free"
  namespace           = "AWS/RDS"
  metric_name         = "FreeableMemory"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 256 * 1024 * 1024
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_connections" {
  alarm_name          = "${local.prefix} database connections near the pool limit"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  # Every backend instance at its pool size, plus migrations and an admin session.
  threshold     = floor(0.9 * (var.max_instances * var.db_pool_size + 5))
  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions
}
