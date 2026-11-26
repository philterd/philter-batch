data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

# The role behind the caller's session, for the default admin access entry (an access entry takes the role, not
# the assumed-role session).
data "aws_iam_session_context" "current" {
  arn = data.aws_caller_identity.current.arn
}

data "aws_availability_zones" "available" {
  count = var.create_vpc ? 1 : 0
  state = "available"
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# S3's addresses in the region, for the chart's NetworkPolicy egress to the store.
data "aws_ec2_managed_prefix_list" "s3" {
  name = "com.amazonaws.${var.region}.s3"
}

locals {
  # --- Images, from the file push-images.sh copies by --------------------------------------------------------------
  images_file = "${path.module}/../../helm/philter-batch/images.yaml"
  images      = yamldecode(file(local.images_file)).images
  digests = {
    for name, image in local.images : name => (
      try(image.digest, "") != "" ? image.digest : lookup(var.image_digests, name, "")
    )
  }
  digest_mismatches = [
    for name, digest in var.image_digests : name
    if try(local.images[name].digest, "") != "" && local.images[name].digest != digest
  ]
  unknown_images  = [for name in keys(var.image_digests) : name if !contains(keys(local.images), name)]
  missing_digests = [for name, digest in local.digests : name if digest == ""]
  registry        = coalesce(var.ecr_registry, "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com")
  registry_id     = split(".", local.registry)[0]
  argo_tag        = { for name in ["workflow-controller", "argoexec", "argo-workflows-crdinstaller"] : name => "${local.images[name].tag}@${local.digests[name]}" }

  # --- Placement: one label and one taint, shared by the node groups and the chart values ---------------------------
  node_pool_label  = "philterd.ai/node-pool"
  system_selector  = { (local.node_pool_label) = "system" }
  workers_selector = { (local.node_pool_label) = "workers" }
  workers_tolerations = [{
    key      = local.node_pool_label
    operator = "Equal"
    value    = "workers"
    effect   = "NoSchedule"
  }]

  # --- The chart's service accounts, bound to the IAM roles ---------------------------------------------------------
  release_name            = "philter-batch"
  workers_service_account = "philter-batch"
  console_service_account = "philter-batch-console"

  # --- Endpoints ----------------------------------------------------------------------------------------------------
  # What private EKS nodes need, from https://docs.aws.amazon.com/eks/latest/userguide/private-clusters.html: EC2
  # (the VPC CNI and node names), ECR (images), EKS, and EKS Auth (Pod Identity). S3 is the gateway endpoint below.
  # The access host adds what SSM Session Manager needs
  # (https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html). Not created, since nothing
  # here uses them: sts and oidc-eks (IRSA), elasticloadbalancing, logs, xray, acm, route53, autoscaling.
  cluster_endpoint_services = ["ec2", "ecr.api", "ecr.dkr", "eks", "eks-auth"]
  ssm_endpoint_services     = ["ssm", "ssmmessages", "ec2messages"]
  interface_endpoint_services = concat(local.cluster_endpoint_services,
  var.access_host_enabled ? local.ssm_endpoint_services : [])

  input_bucket_arn  = "arn:${data.aws_partition.current.partition}:s3:::${var.input_bucket}"
  output_bucket_arn = "arn:${data.aws_partition.current.partition}:s3:::${var.output_bucket}"

  # The S3 gateway endpoint allows only the input (read), the output prefix (read and write), and ECR's layer bucket,
  # from which nodes pull image layers (https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html).
  s3_endpoint_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EcrImageLayers"
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:GetObject"]
        Resource  = ["arn:${data.aws_partition.current.partition}:s3:::prod-${var.region}-starport-layer-bucket/*"]
      },
      {
        Sid       = "ReadInput"
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:ListBucket", "s3:GetObject"]
        Resource  = [local.input_bucket_arn, "${local.input_bucket_arn}/${var.input_prefix}*"]
      },
      {
        Sid       = "ReadWriteOutput"
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource  = [local.output_bucket_arn, "${local.output_bucket_arn}/${var.output_prefix}*"]
      },
    ]
  }

  # --- The API server, for the Helm provider and the destroy check --------------------------------------------------
  endpoint_host = trimprefix(module.eks.cluster_endpoint, "https://")
  api_server    = coalesce(var.kubernetes_api_server, module.eks.cluster_endpoint)

  # --- Chart values ---------------------------------------------------------------------------------------------------
  argo_values = {
    server = { enabled = false } # the console replaces it; nothing listens outside the cluster
    # The Job that applies Argo's full CRDs before install, with its image from the customer's registry.
    crds = {
      upgradeJob = {
        image        = { registry = local.registry, repository = local.images["argo-workflows-crdinstaller"].repository, tag = local.argo_tag["argo-workflows-crdinstaller"] }
        nodeSelector = local.system_selector
      }
    }
    controller = {
      image        = { registry = local.registry, repository = local.images["workflow-controller"].repository, tag = local.argo_tag["workflow-controller"] }
      nodeSelector = local.system_selector
      # Only workflows that reference a WorkflowTemplate run, as the console's submit does, so the console's
      # permission to create workflows cannot run an arbitrary pod as the workers' service account.
      workflowRestrictions = { templateReferencing = "Strict" }
    }
    executor = {
      image = { registry = local.registry, repository = local.images["argoexec"].repository, tag = local.argo_tag["argoexec"] }
    }
  }

  philter_batch_values = {
    image = merge(
      { registry = local.registry },
      try(local.images["philter-batch"].digest, "") == "" ? { digest = local.digests["philter-batch"] } : {}
    )
    store          = { type = "s3", region = var.region }
    output         = { bucket = var.output_bucket, prefix = var.output_prefix }
    serviceAccount = { create = true, name = local.workers_service_account }
    console = {
      serviceAccount = { create = true, name = local.console_service_account }
      nodeSelector   = local.system_selector
    }
    workers = {
      count        = var.workers.count
      cpu          = var.workers.cpu
      memory       = var.workers.memory
      threads      = var.workers.threads
      nodeSelector = local.workers_selector
      tolerations  = local.workers_tolerations
    }
    steps = { nodeSelector = local.system_selector }
    networkPolicy = {
      enabled = true
      # The cluster's private endpoint has its addresses in the cluster's subnets. The kubernetes Service's ClusterIP
      # is listed too, for a CNI that matches the address before kube-proxy translates it.
      kubernetesApi = { to = concat(
        [for c in module.network.subnet_cidrs : { ipBlock = { cidr = c } }],
        [{ ipBlock = { cidr = "${module.eks.kubernetes_service_ip}/32" } }]
      ) }
      # S3, reached through the gateway endpoint, at the addresses in the region's S3 prefix list.
      objectStore = { to = [for e in data.aws_ec2_managed_prefix_list.s3.entries : { ipBlock = { cidr = e.cidr } }] }
      # The EKS Pod Identity agent, on each node's link-local address, from which pods get their credentials.
      extraEgress = [{
        to    = [{ ipBlock = { cidr = "169.254.170.23/32" } }]
        ports = [{ port = 80, protocol = "TCP" }]
      }]
    }
  }
}

# Checks across variables, so a bad combination fails the plan.
resource "terraform_data" "input_checks" {
  lifecycle {
    precondition {
      condition = var.input_bucket != var.output_bucket || (
        var.input_prefix != "" && var.output_prefix != "" &&
        !startswith(var.output_prefix, var.input_prefix) && !startswith(var.input_prefix, var.output_prefix)
      )
      error_message = "The output (output_bucket and output_prefix) must not overlap the input (input_bucket and input_prefix)."
    }
    precondition {
      condition     = length(local.digest_mismatches) == 0
      error_message = "image_digests differs from deploy/helm/philter-batch/images.yaml for: ${join(", ", local.digest_mismatches)}."
    }
    precondition {
      condition     = length(local.unknown_images) == 0
      error_message = "image_digests names images not in deploy/helm/philter-batch/images.yaml: ${join(", ", local.unknown_images)}."
    }
    precondition {
      condition     = length(local.missing_digests) == 0
      error_message = "No digest for: ${join(", ", local.missing_digests)}. images.yaml does not pin it; set it in image_digests."
    }
    precondition {
      condition     = var.create_vpc || (var.vpc_id != null && length(var.subnet_ids) >= 2 && (!var.create_vpc_endpoints || length(var.route_table_ids) > 0))
      error_message = "With create_vpc false, give vpc_id, at least two subnet_ids, and (for the S3 endpoint) route_table_ids."
    }
    precondition {
      condition     = !var.create_output_bucket || length(var.output_kms_key_arns) <= 1
      error_message = "A created output bucket takes at most one KMS key in output_kms_key_arns."
    }
  }
}

module "network" {
  source = "./modules/network"

  name                        = var.name
  region                      = var.region
  create_vpc                  = var.create_vpc
  vpc_cidr                    = var.vpc_cidr
  availability_zones          = var.create_vpc ? slice(data.aws_availability_zones.available[0].names, 0, var.availability_zone_count) : []
  vpc_id                      = var.vpc_id
  subnet_ids                  = var.subnet_ids
  route_table_ids             = var.route_table_ids
  create_endpoints            = var.create_vpc_endpoints
  interface_endpoint_services = local.interface_endpoint_services
  s3_endpoint_policy          = jsonencode(local.s3_endpoint_policy)
}

module "eks" {
  source = "./modules/eks"

  name                       = var.name
  kubernetes_version         = var.kubernetes_version
  vpc_id                     = module.network.vpc_id
  vpc_cidr                   = module.network.vpc_cidr
  subnet_ids                 = module.network.subnet_ids
  service_cidr               = var.service_cidr
  public_access_cidrs        = var.cluster_public_access_cidrs
  admin_principal_arn        = coalesce(var.operator_principal_arn, data.aws_iam_session_context.current.issuer_arn)
  addon_versions             = var.addon_versions
  system_nodes               = var.system_nodes
  worker_nodes               = var.worker_nodes
  node_pool_label            = local.node_pool_label
  secrets_kms_key_arn        = var.secrets_kms_key_arn
  cluster_log_types          = var.cluster_log_types
  cluster_log_retention_days = var.cluster_log_retention_days
  access_host_enabled        = var.access_host_enabled
  access_host_instance_type  = var.access_host_instance_type
}

module "iam" {
  source = "./modules/iam"

  name                    = var.name
  region                  = var.region
  cluster_name            = module.eks.cluster_name
  namespace               = var.namespace
  workers_service_account = local.workers_service_account
  console_service_account = local.console_service_account
  input_bucket            = var.input_bucket
  input_prefix            = var.input_prefix
  output_bucket           = var.output_bucket
  output_prefix           = var.output_prefix
  input_kms_key_arns      = var.input_kms_key_arns
  output_kms_key_arns     = var.output_kms_key_arns
}

module "output_bucket" {
  source = "./modules/output-bucket"
  count  = var.create_output_bucket ? 1 : 0

  bucket      = var.output_bucket
  kms_key_arn = try(var.output_kms_key_arns[0], null)
}

# Each image, looked up in the registry at plan time.
data "aws_ecr_image" "images" {
  for_each        = var.check_images_in_ecr ? { for k, v in local.images : k => v if local.digests[k] != "" } : {}
  registry_id     = local.registry_id
  repository_name = each.value.repository
  image_digest    = local.digests[each.key]
}

module "software" {
  source = "./modules/software"
  count  = var.install_software ? 1 : 0

  argo_namespace     = var.argo_namespace
  argo_chart_version = var.argo_workflows_chart_version
  argo_values        = yamlencode(local.argo_values)
  namespace          = var.namespace
  release_name       = local.release_name
  chart_path         = "${path.module}/../../helm/philter-batch"
  chart_values       = concat([yamlencode(local.philter_batch_values)], var.extra_chart_values)

  depends_on_ready = {
    eks            = module.eks.ready
    endpoints      = module.network.endpoint_ids
    pod_identities = module.iam.pod_identity_association_ids
    images         = [for i in data.aws_ecr_image.images : i.id]
    output_bucket  = [for b in module.output_bucket : b.bucket]
  }

  guard = {
    cluster_name = module.eks.cluster_name
    region       = var.region
    server       = var.kubernetes_api_server == null ? "" : var.kubernetes_api_server
    skip         = var.allow_destroy_while_running
  }
}
