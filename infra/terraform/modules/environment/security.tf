# The VPC's default group would let anything placed in it talk to everything else in it. Nothing uses it; this
# removes all its rules.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
}

# Each tier accepts traffic only from the one in front of it: internet -> load balancer -> web app -> backend -> database.

resource "aws_security_group" "alb" {
  name        = "${local.prefix}-alb"
  description = "Public HTTPS, and HTTP only to redirect it"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  description       = "HTTPS from anyone"
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  #checkov:skip=CKV_AWS_260:Port 80 only answers with a redirect to HTTPS
  description       = "HTTP from anyone, only to redirect to HTTPS"
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_frontend" {
  description                  = "To the web app"
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.frontend.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
}

resource "aws_security_group" "frontend" {
  name        = "${local.prefix}-frontend"
  description = "Web app: from the load balancer only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "frontend_from_alb" {
  description                  = "From the load balancer"
  security_group_id            = aws_security_group.frontend.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
}

resource "aws_vpc_security_group_egress_rule" "frontend_out" {
  description = "To the backend, image registry and Secrets Manager"
  # The backend, and HTTPS out for pulling images and reading secrets.
  security_group_id = aws_security_group.frontend.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "backend" {
  name        = "${local.prefix}-backend"
  description = "Backend API: from the web app only; never reachable from the load balancer or the internet"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "backend_from_frontend" {
  description                  = "From the web app"
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.frontend.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_egress_rule" "backend_out" {
  description = "To the database, S3, SendGrid and Razorpay"
  # The database, S3, SendGrid and Razorpay.
  security_group_id = aws_security_group.backend.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "db_admin" {
  #checkov:skip=CKV2_AWS_5:Attached by infra/scripts/db-setup.sh when it runs the setup task
  name        = "${local.prefix}-db-admin"
  description = "One-off database setup task"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_egress_rule" "db_admin_out" {
  description       = "To the database, image registry and Secrets Manager"
  security_group_id = aws_security_group.db_admin.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "db" {
  name        = "${local.prefix}-db"
  description = "PostgreSQL: from the backend and the setup task only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_backend" {
  description                  = "From the backend"
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.backend.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "db_from_admin" {
  description                  = "From the setup task"
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.db_admin.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
