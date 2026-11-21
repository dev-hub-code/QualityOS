# Shared by every environment: Terraform state, and the container images each environment runs.

data "aws_caller_identity" "current" {}

locals {
  # Bucket names are global, so the account number keeps this one ours.
  state_bucket = "qualityos-terraform-state-${data.aws_caller_identity.current.account_id}"
  images       = toset(["backend", "frontend"])
}

resource "aws_s3_bucket" "state" {
  #checkov:skip=CKV_AWS_18:Only Terraform touches it, and CloudTrail records who did
  #checkov:skip=CKV_AWS_144:Versioned; copying state to a second region is not needed yet
  #checkov:skip=CKV2_AWS_62:Nothing reacts to state changes
  #checkov:skip=CKV_AWS_145:Encrypted with S3-managed keys and holds no secrets (see secrets.tf)
  bucket = local.state_bucket

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    # A bad apply can be undone by restoring the previous state version.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "state_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket     = aws_s3_bucket.state.id
  policy     = data.aws_iam_policy_document.state_tls_only.json
  depends_on = [aws_s3_bucket_public_access_block.state]
}

resource "aws_ecr_repository" "image" {
  #checkov:skip=CKV_AWS_136:Encrypted with AES-256; images hold no secrets
  for_each = local.images

  name = "qualityos-${each.key}"
  # A tag always names the same image, so a deployed git commit can be traced to exactly what runs.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "image" {
  for_each   = aws_ecr_repository.image
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 50 images, enough to roll back well past any deploy still in UAT or production"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 50
      }
      action = { type = "expire" }
    }]
  })
}
