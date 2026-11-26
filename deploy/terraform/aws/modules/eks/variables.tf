variable "name" {
  description = "The cluster's name, also the prefix of its other resources."
  type        = string
}

variable "kubernetes_version" {
  description = "The cluster's Kubernetes version."
  type        = string
}

variable "vpc_id" {
  description = "The VPC."
  type        = string
}

variable "vpc_cidr" {
  description = "The VPC's CIDR block."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets for the cluster's endpoint and the nodes."
  type        = list(string)
}

variable "service_cidr" {
  description = "The Kubernetes Service CIDR. Must not overlap the VPC."
  type        = string
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to reach the cluster's public endpoint. Empty: no public endpoint."
  type        = list(string)
}

variable "admin_principal_arn" {
  description = "The IAM principal given cluster admin through an access entry: the operator's role or user."
  type        = string
}

variable "addon_versions" {
  description = "Versions of the EKS add-ons, by name. An add-on not listed gets EKS's default version for the cluster's Kubernetes version."
  type        = map(string)
  default     = {}
}

variable "system_nodes" {
  description = "The on-demand node group, for Argo's controller, the console, and the workflow's small steps."
  type = object({
    instance_types = list(string)
    desired_size   = number
    min_size       = number
    max_size       = number
    disk_size_gib  = number
  })
}

variable "worker_nodes" {
  description = "The worker node group."
  type = object({
    instance_types = list(string)
    capacity_type  = string
    desired_size   = number
    min_size       = number
    max_size       = number
    disk_size_gib  = number
  })
}

variable "node_pool_label" {
  description = "The node label that names a node's pool, and the key of the workers' taint."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "A KMS key for envelope encryption of Kubernetes secrets. Null: EKS's default encryption with an AWS owned key."
  type        = string
  default     = null
}

variable "cluster_log_types" {
  description = "Control plane log types sent to CloudWatch Logs."
  type        = list(string)
}

variable "cluster_log_retention_days" {
  description = "Retention of the control plane log group."
  type        = number
}

variable "access_host_enabled" {
  description = "Create an instance with no inbound access that the operator reaches with SSM Session Manager, to forward a local port to the cluster's private endpoint."
  type        = bool
}

variable "access_host_instance_type" {
  description = "The access host's instance type."
  type        = string
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
