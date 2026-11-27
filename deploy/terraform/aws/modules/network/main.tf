# Private subnets for the nodes. By default they have no route to the internet: no internet gateway and no NAT
# gateway, and nodes reach AWS only through the VPC endpoints below. With internet_access (and create_vpc), one NAT
# gateway in a small public subnet gives them outbound access, for pulling images from public registries; the
# endpoints are still used for AWS. Nothing is reachable from the internet: there is no inbound route to the nodes.

locals {
  vpc_id          = var.create_vpc ? aws_vpc.this[0].id : var.vpc_id
  vpc_cidr        = var.create_vpc ? aws_vpc.this[0].cidr_block : data.aws_vpc.existing[0].cidr_block
  subnet_ids      = var.create_vpc ? aws_subnet.private[*].id : var.subnet_ids
  subnet_cidrs    = var.create_vpc ? aws_subnet.private[*].cidr_block : [for s in data.aws_subnet.existing : s.cidr_block]
  route_table_ids = var.create_vpc ? [aws_route_table.private[0].id] : var.route_table_ids
  create_nat      = var.create_vpc && var.internet_access
  # The public subnet is the VPC's last /20, after the private subnets (at most six /20s from the start).
  public_subnet_cidr = var.create_vpc ? cidrsubnet(var.vpc_cidr, 4, 15) : null
}

data "aws_vpc" "existing" {
  count = var.create_vpc ? 0 : 1
  id    = var.vpc_id
}

data "aws_subnet" "existing" {
  for_each = var.create_vpc ? toset([]) : toset(var.subnet_ids)
  id       = each.value
}

# Flow logs are left to the customer's own VPC logging: this VPC exists only for one run and carries no inbound
# traffic.
#trivy:ignore:AVD-AWS-0178
resource "aws_vpc" "this" {
  count                = var.create_vpc ? 1 : 0
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # needed for the endpoints' private DNS and the cluster's private endpoint
  tags                 = merge(var.tags, { Name = var.name })
}

# The default security group allows nothing.
resource "aws_default_security_group" "this" {
  count  = var.create_vpc ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = merge(var.tags, { Name = "${var.name}-default" })
}

resource "aws_subnet" "private" {
  count                   = var.create_vpc ? length(var.availability_zones) : 0
  vpc_id                  = aws_vpc.this[0].id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false
  tags                    = merge(var.tags, { Name = "${var.name}-private-${var.availability_zones[count.index]}" })
}

# One route table with the local route, S3 from the gateway endpoint, and, with internet_access only, a default
# route to the NAT gateway. Setting `route` as an attribute makes Terraform remove any other route added later, such
# as a default route to a NAT or internet gateway when internet_access is off. The provider leaves gateway endpoint
# routes out of this attribute, so the S3 route is unaffected.
resource "aws_route_table" "private" {
  count  = var.create_vpc ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  route = local.create_nat ? [{
    cidr_block                 = "0.0.0.0/0"
    nat_gateway_id             = aws_nat_gateway.this[0].id
    ipv6_cidr_block            = null
    destination_prefix_list_id = null
    carrier_gateway_id         = null
    core_network_arn           = null
    egress_only_gateway_id     = null
    gateway_id                 = null
    local_gateway_id           = null
    network_interface_id       = null
    transit_gateway_id         = null
    vpc_endpoint_id            = null
    vpc_peering_connection_id  = null
    odb_network_arn            = null
  }] : []
  tags = merge(var.tags, { Name = "${var.name}-private" })
}

# --- internet_access only: an internet gateway, a public subnet, and one NAT gateway ---------------------------------
# One NAT gateway, in the first zone, is enough for a temporary stack: if that zone fails, image pulls fail and the
# run resumes when pods can start again.

resource "aws_internet_gateway" "this" {
  count  = local.create_nat ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "public" {
  count                   = local.create_nat ? 1 : 0
  vpc_id                  = aws_vpc.this[0].id
  availability_zone       = var.availability_zones[0]
  cidr_block              = local.public_subnet_cidr
  map_public_ip_on_launch = false # only the NAT gateway has a public address
  tags                    = merge(var.tags, { Name = "${var.name}-public-${var.availability_zones[0]}" })
}

resource "aws_route_table" "public" {
  count  = local.create_nat ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route" "public_internet" {
  count                  = local.create_nat ? 1 : 0
  route_table_id         = aws_route_table.public[0].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this[0].id
}

resource "aws_route_table_association" "public" {
  count          = local.create_nat ? 1 : 0
  subnet_id      = aws_subnet.public[0].id
  route_table_id = aws_route_table.public[0].id
}

resource "aws_eip" "nat" {
  count  = local.create_nat ? 1 : 0
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count         = local.create_nat ? 1 : 0
  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public[0].id
  tags          = merge(var.tags, { Name = var.name })

  depends_on = [aws_internet_gateway.this, aws_route_table_association.public]
}

resource "aws_route_table_association" "private" {
  count          = var.create_vpc ? length(var.availability_zones) : 0
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[0].id
}

resource "aws_vpc_endpoint" "s3" {
  count             = var.create_endpoints ? 1 : 0
  vpc_id            = local.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.route_table_ids
  policy            = var.s3_endpoint_policy
  tags              = merge(var.tags, { Name = "${var.name}-s3" })
}

resource "aws_security_group" "endpoints" {
  count       = var.create_endpoints ? 1 : 0
  name        = "${var.name}-endpoints"
  description = "Interface VPC endpoints: HTTPS from the VPC"
  vpc_id      = local.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-endpoints" })
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  count             = var.create_endpoints ? 1 : 0
  security_group_id = aws_security_group.endpoints[0].id
  description       = "HTTPS from the VPC"
  cidr_ipv4         = local.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = var.create_endpoints ? toset(var.interface_endpoint_services) : toset([])
  vpc_id              = local.vpc_id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = local.subnet_ids
  security_group_ids  = [aws_security_group.endpoints[0].id]
  tags                = merge(var.tags, { Name = "${var.name}-${each.value}" })
}

# On destroy, after the cluster and its nodes are gone and before the subnets are: deletes network interfaces the
# VPC CNI created and left behind (status available), which would otherwise block deleting the subnets. The
# cluster depends on this through the subnet_ids output, so Terraform destroys the cluster first. Needs the AWS CLI.
resource "terraform_data" "cni_eni_cleanup" {
  input = {
    vpc_id = local.vpc_id
    region = var.region
    script = "${path.root}/scripts/cleanup-cni-enis.sh"
  }

  provisioner "local-exec" {
    when    = destroy
    command = "${self.input.script} --region ${self.input.region} --vpc-id ${self.input.vpc_id}"
  }

  depends_on = [aws_subnet.private, aws_route_table_association.private, aws_vpc_endpoint.interface]
}
