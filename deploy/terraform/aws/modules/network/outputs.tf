output "vpc_id" {
  description = "The VPC."
  value       = local.vpc_id
}

output "vpc_cidr" {
  description = "The VPC's CIDR block."
  value       = local.vpc_cidr
}

output "subnet_ids" {
  description = "The private subnets. Depends on the CNI cleanup, so that the cluster is destroyed before it runs."
  value       = local.subnet_ids
  depends_on  = [terraform_data.cni_eni_cleanup]
}

output "subnet_cidrs" {
  description = "The private subnets' CIDR blocks, where the cluster's private API endpoint has its addresses."
  value       = local.subnet_cidrs
}

output "interface_endpoint_services" {
  description = "The interface endpoints created."
  value       = sort(keys(aws_vpc_endpoint.interface))
}

output "endpoint_ids" {
  description = "The VPC endpoints, so that a destroy keeps them (SSM's in particular) until the releases are gone."
  value       = concat([for e in aws_vpc_endpoint.interface : e.id], [for e in aws_vpc_endpoint.s3 : e.id])
}

output "route_table_routes" {
  description = "The routes the new VPC's route table holds besides the local and gateway endpoint routes: none."
  value       = var.create_vpc ? aws_route_table.private[0].route : null
}

output "s3_endpoint" {
  description = "The S3 gateway endpoint's type and policy, or null."
  value = var.create_endpoints ? {
    type   = aws_vpc_endpoint.s3[0].vpc_endpoint_type
    policy = aws_vpc_endpoint.s3[0].policy
  } : null
}

output "interface_endpoints_private_dns" {
  description = "Whether each interface endpoint has private DNS on."
  value       = { for k, e in aws_vpc_endpoint.interface : k => e.private_dns_enabled }
}
