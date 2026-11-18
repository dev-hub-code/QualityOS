data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:*"]
    }
  }
}

# Used by ECS itself to pull images, write logs and hand the task its secrets. Application code never holds it.

resource "aws_iam_role" "execution" {
  name               = "${local.prefix}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.app.arn]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "app-secret"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# The setup task alone may read the database admin password.

resource "aws_iam_role" "db_admin_execution" {
  name               = "${local.prefix}-db-admin-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "db_admin_execution" {
  role       = aws_iam_role.db_admin_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "db_admin_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.app.arn, aws_db_instance.main.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "db_admin_secrets" {
  name   = "db-secrets"
  role   = aws_iam_role.db_admin_execution.id
  policy = data.aws_iam_policy_document.db_admin_secrets.json
}

# What the running application may do: its own bucket, nothing else.

data "aws_iam_policy_document" "exec_command" {
  # ECS Exec, for a shell in a running task when diagnosing UAT.
  statement {
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role" "backend" {
  name               = "${local.prefix}-backend"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "backend" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.files.arn}/*"]
  }
  statement {
    # Without it, S3 answers a missing file with 403 instead of 404, and a missing file looks like a fault.
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.files.arn]
  }
}

resource "aws_iam_role_policy" "backend_files" {
  name   = "files"
  role   = aws_iam_role.backend.id
  policy = data.aws_iam_policy_document.backend.json
}

resource "aws_iam_role_policy" "backend_exec" {
  name   = "exec-command"
  role   = aws_iam_role.backend.id
  policy = data.aws_iam_policy_document.exec_command.json
}

resource "aws_iam_role" "frontend" {
  name               = "${local.prefix}-frontend"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "frontend_exec" {
  name   = "exec-command"
  role   = aws_iam_role.frontend.id
  policy = data.aws_iam_policy_document.exec_command.json
}
