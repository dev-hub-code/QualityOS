data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.region}.amazonaws.com"
}

# Built as production will be (two instances of each service, a database standby, every alarm) but at UAT sizes.
module "environment" {
  source = "../../modules/environment"

  name              = "uat"
  domain_name       = var.domain_name
  route53_zone_name = var.route53_zone_name
  alert_email       = var.alert_email
  mail_from         = var.mail_from
  app_environment   = var.app_environment

  backend_image  = "${local.registry}/qualityos-backend:${var.image_tag}"
  frontend_image = "${local.registry}/qualityos-frontend:${var.image_tag}"

  vpc_cidr          = "10.20.0.0/16"
  nat_gateways      = 1
  db_instance_class = "db.t4g.medium"
  db_multi_az       = true

  # A shell in running containers, for diagnosing UAT. Off in production.
  enable_exec_command = true
}
