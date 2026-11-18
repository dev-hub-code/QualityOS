# Web application firewall in front of the load balancer: a per-address rate limit for floods, AWS's list of known bad
# addresses, and AWS's common and known-bad-input rules. The application keeps its own sign-in limits behind it.

resource "aws_wafv2_web_acl" "main" {
  name        = local.prefix
  description = "QualityOS ${var.name}: rate limit and AWS managed rules"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-per-address"
    priority = 0
    action {
      block {}
    }
    statement {
      rate_based_statement {
        # A whole factory can share one address, so this is set for floods, not for people.
        limit                 = var.waf_requests_per_5_minutes
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }
    visibility_config {
      metric_name                = "${local.prefix}-rate-limit"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 1
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }
    visibility_config {
      metric_name                = "${local.prefix}-ip-reputation"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 2
    override_action {
      none {}
    }
    statement {
      # Includes Log4j and other exploit patterns.
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    visibility_config {
      metric_name                = "${local.prefix}-known-bad-inputs"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 3
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        # Attachments and logos are larger than this rule's 8 KB body limit; the application enforces its own limits.
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }
    visibility_config {
      metric_name                = "${local.prefix}-common"
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    metric_name                = local.prefix
    cloudwatch_metrics_enabled = true
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.main.arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}

# Blocked and counted requests only, to see false positives without paying to log every request.
resource "aws_cloudwatch_log_group" "waf" {
  #checkov:skip=CKV_AWS_158:Encrypted by CloudWatch with its own key; a customer-managed key is a production decision
  # WAF requires this prefix.
  name              = "aws-waf-logs-${local.prefix}"
  retention_in_days = var.log_retention_days
}

resource "aws_wafv2_web_acl_logging_configuration" "main" {
  resource_arn            = aws_wafv2_web_acl.main.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  # Session cookies and tokens never reach the log.
  redacted_fields {
    single_header {
      name = "cookie"
    }
  }
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  logging_filter {
    default_behavior = "DROP"
    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"
      condition {
        action_condition {
          action = "BLOCK"
        }
      }
      condition {
        action_condition {
          action = "COUNT"
        }
      }
    }
  }
}
