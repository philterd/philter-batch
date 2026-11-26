variable "name" {
  description = "Name prefix for every resource."
  type        = string
}

variable "region" {
  description = "The AWS region."
  type        = string
}

variable "create_vpc" {
  description = "Create the VPC and its private subnets. When false, vpc_id, subnet_ids, and route_table_ids are used."
  type        = bool
}

variable "vpc_cidr" {
  description = "The new VPC's CIDR block."
  type        = string
}

variable "availability_zones" {
  description = "The availability zones of the new VPC's private subnets, one subnet each."
  type        = list(string)
}

variable "vpc_id" {
  description = "An existing VPC."
  type        = string
  default     = null
}

variable "subnet_ids" {
  description = "Existing private subnets, in at least two availability zones."
  type        = list(string)
  default     = []
}

variable "route_table_ids" {
  description = "The existing subnets' route tables, for the S3 gateway endpoint."
  type        = list(string)
  default     = []
}

variable "create_endpoints" {
  description = "Create the VPC endpoints. Turn off only when the VPC already has every endpoint in interface_endpoint_services and an S3 gateway endpoint whose policy allows what s3_endpoint_policy does."
  type        = bool
}

variable "interface_endpoint_services" {
  description = "Interface endpoint services, as the part of the service name after com.amazonaws.<region>."
  type        = list(string)
}

variable "s3_endpoint_policy" {
  description = "The S3 gateway endpoint's policy, as JSON."
  type        = string
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
