# Three tiers in each zone. Only the load balancer is in a public subnet; the application and the database have no
# public address, and the database has no route out at all.

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = local.prefix }
}

resource "aws_subnet" "public" {
  count             = length(local.zones)
  vpc_id            = aws_vpc.main.id
  availability_zone = local.zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)
  tags              = { Name = "${local.prefix}-public-${local.zones[count.index]}", Tier = "public" }
}

resource "aws_subnet" "app" {
  count             = length(local.zones)
  vpc_id            = aws_vpc.main.id
  availability_zone = local.zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 10 + count.index)
  tags              = { Name = "${local.prefix}-app-${local.zones[count.index]}", Tier = "app" }
}

resource "aws_subnet" "db" {
  count             = length(local.zones)
  vpc_id            = aws_vpc.main.id
  availability_zone = local.zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 20 + count.index)
  tags              = { Name = "${local.prefix}-db-${local.zones[count.index]}", Tier = "db" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = local.prefix }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "${local.prefix}-public" }
}

resource "aws_route_table_association" "public" {
  count          = length(local.zones)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# The application reaches SendGrid, Razorpay, the image registry and Secrets Manager through NAT.
resource "aws_eip" "nat" {
  count  = var.nat_gateways
  domain = "vpc"
  tags   = { Name = "${local.prefix}-nat-${count.index}" }
}

resource "aws_nat_gateway" "main" {
  count         = var.nat_gateways
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = { Name = "${local.prefix}-${local.zones[count.index]}" }
  depends_on    = [aws_internet_gateway.main]
}

resource "aws_route_table" "app" {
  count  = length(local.zones)
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    # Each zone uses its own gateway when there is one per zone, otherwise they share.
    nat_gateway_id = aws_nat_gateway.main[count.index % var.nat_gateways].id
  }
  tags = { Name = "${local.prefix}-app-${local.zones[count.index]}" }
}

resource "aws_route_table_association" "app" {
  count          = length(local.zones)
  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

resource "aws_route_table" "db" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.prefix}-db" }
}

resource "aws_route_table_association" "db" {
  count          = length(local.zones)
  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}

# Files go to S3 inside AWS's network, free of NAT charges.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id
  tags              = { Name = "${local.prefix}-s3" }
}
