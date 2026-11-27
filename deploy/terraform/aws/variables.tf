# --- Where ---------------------------------------------------------------------------------------------------------

variable "region" {
  description = "The AWS region of the cluster, the buckets, and the ECR registry."
  type        = string
}

variable "name" {
  description = "The cluster's name and the prefix of every resource."
  type        = string
  default     = "philter-batch"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.name))
    error_message = "name must be 2 to 31 lowercase letters, digits, and hyphens, starting with a letter."
  }
}

variable "tags" {
  description = "Tags for every AWS resource."
  type        = map(string)
  default     = {}
}

# --- The data ------------------------------------------------------------------------------------------------------

variable "input_bucket" {
  description = "The bucket holding the documents. Only referenced: the stack never creates, changes, or deletes it."
  type        = string
}

variable "input_prefix" {
  description = "The prefix under which the documents are. Empty for the whole bucket."
  type        = string
  default     = ""
}

variable "input_kms_key_arns" {
  description = "KMS keys the input is encrypted with (SSE-KMS). The workers may decrypt with them, through S3 only."
  type        = list(string)
  default     = []
}

variable "output_bucket" {
  description = "The bucket runs write to. Referenced unless create_output_bucket is true."
  type        = string
}

variable "output_prefix" {
  description = "The prefix under which runs write (<prefix>runs/<run_id>/). Empty, or ending with /."
  type        = string
  default     = "philter-batch/"

  validation {
    condition     = var.output_prefix == "" || endswith(var.output_prefix, "/")
    error_message = "output_prefix must be empty or end with /."
  }
}

variable "output_kms_key_arns" {
  description = "KMS keys the output bucket encrypts with (SSE-KMS). The workers and the console may use them, through S3 only. When the stack creates the bucket, the first is its default key."
  type        = list(string)
  default     = []
}

variable "create_output_bucket" {
  description = "Create the output bucket. It is kept on destroy (prevent_destroy); see docs/aws.md."
  type        = bool
  default     = false
}

# --- Network -------------------------------------------------------------------------------------------------------

variable "internet_access" {
  description = "Give the nodes a route to the internet and pull the images from their public registries (Docker Hub and quay.io), by digest. False (the default): no route to the internet, and the images come from the account's ECR, copied there by scripts/push-images.sh. With create_vpc, true adds an internet gateway, a public subnet, and one NAT gateway; with an existing VPC, its subnets must already route to the internet."
  type        = bool
  default     = false
}

variable "create_vpc" {
  description = "Create a VPC with private subnets (and, with internet_access, a NAT gateway). When false, give vpc_id, subnet_ids, and route_table_ids."
  type        = bool
  default     = true
}

variable "vpc_cidr" {
  description = "The new VPC's CIDR block. Each private subnet is a /20 of it."
  type        = string
  default     = "10.80.0.0/16"
}

variable "availability_zone_count" {
  description = "How many availability zones the new VPC's private subnets span."
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 6
    error_message = "availability_zone_count must be between 2 and 6: EKS needs subnets in at least two zones."
  }
}

variable "vpc_id" {
  description = "An existing VPC, when create_vpc is false. It needs DNS support and DNS hostnames turned on."
  type        = string
  default     = null
}

variable "subnet_ids" {
  description = "Existing private subnets in at least two availability zones, when create_vpc is false."
  type        = list(string)
  default     = []
}

variable "route_table_ids" {
  description = "The existing subnets' route tables, for the S3 gateway endpoint, when create_vpc is false."
  type        = list(string)
  default     = []
}

variable "create_vpc_endpoints" {
  description = "Create the S3 gateway endpoint and the interface endpoints. Turn off only for an existing VPC that already has all of them, with an S3 endpoint policy that allows the same buckets."
  type        = bool
  default     = true
}

# --- Cluster -------------------------------------------------------------------------------------------------------

variable "kubernetes_version" {
  description = "The cluster's Kubernetes version."
  type        = string
  default     = "1.35"
}

variable "service_cidr" {
  description = "The cluster's Kubernetes Service CIDR. Must not overlap the VPC or any network the pods reach."
  type        = string
  default     = "172.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.service_cidr, 1))
    error_message = "service_cidr must be a CIDR block."
  }
}

variable "addon_versions" {
  description = "EKS add-on versions by name (vpc-cni, kube-proxy, eks-pod-identity-agent, coredns). Unlisted add-ons get EKS's default for the Kubernetes version."
  type        = map(string)
  default     = {}
}

variable "cluster_public_access_cidrs" {
  description = "CIDRs allowed to reach a public cluster endpoint. Empty (the default): the endpoint is private only."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.cluster_public_access_cidrs : can(cidrhost(c, 0)) && c != "0.0.0.0/0"])
    error_message = "cluster_public_access_cidrs must be CIDR blocks, and not 0.0.0.0/0."
  }
}

variable "access_host_enabled" {
  description = "Create an SSM access host with no inbound access, through which the operator forwards a local port to the private cluster endpoint. Turn off when the operator reaches the VPC another way, or uses cluster_public_access_cidrs."
  type        = bool
  default     = true
}

variable "access_host_instance_type" {
  description = "The access host's instance type."
  type        = string
  default     = "t3.micro"
}

variable "kubernetes_api_server" {
  description = "The address Terraform's Helm provider and the destroy check use for the cluster's API, such as https://127.0.0.1:8443 through the access host's port forward. Null: the cluster's own endpoint."
  type        = string
  default     = null

  validation {
    condition     = var.kubernetes_api_server == null || can(regex("^https://[^ ]+$", var.kubernetes_api_server))
    error_message = "kubernetes_api_server must be an https:// URL."
  }
}

variable "operator_principal_arn" {
  description = "The IAM role or user given cluster admin through an EKS access entry. Null: the role or user running Terraform."
  type        = string
  default     = null
}

variable "secrets_kms_key_arn" {
  description = "A KMS key for envelope encryption of Kubernetes secrets. Null: EKS's default encryption with an AWS owned key."
  type        = string
  default     = null
}

variable "cluster_log_types" {
  description = "Control plane logs sent to CloudWatch Logs."
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "cluster_log_retention_days" {
  description = "Retention of the control plane log group, which is deleted with the stack."
  type        = number
  default     = 90
}

variable "system_nodes" {
  description = "The on-demand node group: Argo's controller, the console, CoreDNS, and the workflow's small steps (inventory and finalize need about 0.5 KB of memory per input file)."
  type = object({
    instance_types = optional(list(string), ["m7i.xlarge", "m6i.xlarge"])
    desired_size   = optional(number, 2)
    min_size       = optional(number, 1)
    max_size       = optional(number, 3)
    disk_size_gib  = optional(number, 50)
  })
  default = {}
}

variable "worker_nodes" {
  description = "The worker node group: one worker pod per node with the default sizes. capacity_type is SPOT or ON_DEMAND."
  type = object({
    instance_types = optional(list(string), ["c7i.2xlarge", "c6i.2xlarge", "m7i.2xlarge", "m6i.2xlarge"])
    capacity_type  = optional(string, "SPOT")
    desired_size   = optional(number, 4)
    min_size       = optional(number, 0)
    max_size       = optional(number, 20)
    disk_size_gib  = optional(number, 50)
  })
  default = {}

  validation {
    condition     = contains(["SPOT", "ON_DEMAND"], var.worker_nodes.capacity_type)
    error_message = "worker_nodes.capacity_type must be SPOT or ON_DEMAND."
  }
}

# --- Images --------------------------------------------------------------------------------------------------------

variable "ecr_registry" {
  description = "The registry scripts/push-images.sh copied the images to. Null: this account's ECR registry in the region. Not used with internet_access."
  type        = string
  default     = null
}

variable "image_digests" {
  description = "Image digests by name in deploy/helm/philter-batch/images.yaml. Required for an image images.yaml does not pin (philter-batch, until it is published); must equal the pinned digest otherwise."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for d in values(var.image_digests) : can(regex("^sha256:[a-f0-9]{64}$", d))])
    error_message = "Each image digest must be sha256: followed by 64 lowercase hex characters."
  }
}

variable "check_images_in_ecr" {
  description = "Look up each image in ECR at plan time, so a missing image fails the plan rather than a pod. Not used with internet_access."
  type        = bool
  default     = true
}

# --- Software ------------------------------------------------------------------------------------------------------

variable "install_software" {
  description = "Install Argo Workflows and Philter Batch with Helm. False for the first apply when the API is reached through the access host, which does not exist yet."
  type        = bool
  default     = true
}

variable "argo_workflows_chart_version" {
  description = "The argo-workflows chart's version. 2.0.18 (Argo Workflows v4.1.6) is the one tested with Philter Batch."
  type        = string
  default     = "2.0.18"
}

variable "namespace" {
  description = "Philter Batch's namespace."
  type        = string
  default     = "philter-batch"
}

variable "argo_namespace" {
  description = "Argo Workflows' namespace."
  type        = string
  default     = "argo"
}

variable "workers" {
  description = "The chart's worker pool: pods, CPUs and memory per pod, and threads per pod."
  type = object({
    count   = optional(number, 4)
    cpu     = optional(number, 4)
    memory  = optional(string, "6Gi")
    threads = optional(number, 1)
  })
  default = {}
}

variable "extra_chart_values" {
  description = "Further values for the Philter Batch chart, as YAML documents, applied after the stack's own."
  type        = list(string)
  default     = []
}

variable "allow_destroy_while_running" {
  description = "Skip the destroy-time check for running workflows. Takes effect after an apply."
  type        = bool
  default     = false
}
