# Plans the whole stack against mocked AWS and Helm providers: no AWS account, credentials, or cluster. Invented
# names throughout. Run from deploy/terraform/aws: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
      arn        = "arn:aws:sts::111122223333:assumed-role/example-operator/session"
    }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_iam_session_context" {
    defaults = { issuer_arn = "arn:aws:iam::111122223333:role/example-operator" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["us-east-1a", "us-east-1b", "us-east-1c"] }
  }
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = {
      entries = [
        { cidr = "198.51.100.0/24", description = "" },
        { cidr = "203.0.113.0/24", description = "" },
      ]
    }
  }
  mock_data "aws_eks_addon_version" {
    defaults = { version = "v1.0.0-eksbuild.1" }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { insecure_value = "ami-0123456789abcdef0" }
  }
  mock_resource "aws_eks_cluster" {
    override_during = plan
    defaults = {
      arn                   = "arn:aws:eks:us-east-1:111122223333:cluster/philter-batch"
      endpoint              = "https://EXAMPLE0123456789.gr7.us-east-1.eks.amazonaws.com"
      certificate_authority = [{ data = "ZXhhbXBsZQ==" }]
    }
  }
}

mock_provider "helm" {}

variables {
  region        = "us-east-1"
  input_bucket  = "example-input-bucket"
  input_prefix  = "documents/"
  output_bucket = "example-output-bucket"
  output_prefix = "philter-batch/"
  image_digests = {
    "philter-batch" = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
  }
}

run "endpoints" {
  command = plan

  # Private EKS nodes (private-clusters.html) plus SSM Session Manager for the access host.
  assert {
    condition     = output.interface_endpoints == sort(["ec2", "ecr.api", "ecr.dkr", "eks", "eks-auth", "ssm", "ssmmessages", "ec2messages"])
    error_message = "Unexpected interface endpoints: ${jsonencode(output.interface_endpoints)}"
  }
  assert {
    condition     = alltrue(values(module.network.interface_endpoints_private_dns))
    error_message = "Every interface endpoint needs private DNS, so ECR and EKS names resolve to it."
  }
  assert {
    condition     = module.network.s3_endpoint.type == "Gateway"
    error_message = "S3 must be a gateway endpoint."
  }
}

run "endpoints_without_access_host" {
  command = plan
  variables {
    access_host_enabled = false
  }
  assert {
    condition     = output.interface_endpoints == sort(["ec2", "ecr.api", "ecr.dkr", "eks", "eks-auth"])
    error_message = "Without the access host, the SSM endpoints are not needed: ${jsonencode(output.interface_endpoints)}"
  }
  assert {
    condition     = output.access_host_tunnel == null
    error_message = "No tunnel command without the access host."
  }
}

run "s3_endpoint_policy" {
  command = plan

  assert {
    condition = jsondecode(module.network.s3_endpoint.policy) == {
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "EcrImageLayers"
          Effect    = "Allow"
          Principal = "*"
          Action    = ["s3:GetObject"]
          Resource  = ["arn:aws:s3:::prod-us-east-1-starport-layer-bucket/*"]
        },
        {
          Sid       = "ReadInput"
          Effect    = "Allow"
          Principal = "*"
          Action    = ["s3:ListBucket", "s3:GetObject"]
          Resource  = ["arn:aws:s3:::example-input-bucket", "arn:aws:s3:::example-input-bucket/documents/*"]
        },
        {
          Sid       = "ReadWriteOutput"
          Effect    = "Allow"
          Principal = "*"
          Action    = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
          Resource  = ["arn:aws:s3:::example-output-bucket", "arn:aws:s3:::example-output-bucket/philter-batch/*"]
        },
      ]
    }
    error_message = "Unexpected S3 endpoint policy: ${module.network.s3_endpoint.policy}"
  }
  # The endpoint allows no write anywhere on the input.
  assert {
    condition = alltrue([
      for s in jsondecode(module.network.s3_endpoint.policy).Statement :
      !anytrue([for r in s.Resource : startswith(r, "arn:aws:s3:::example-input-bucket")]) ||
      length(setintersection(toset(s.Action), toset(["s3:PutObject", "s3:DeleteObject"]))) == 0
    ])
    error_message = "The S3 endpoint policy allows writing to the input."
  }
}

run "iam_policies" {
  command = plan

  # Workers: read on the input, never write; write only under the output prefix.
  assert {
    condition = alltrue([
      for s in jsondecode(module.iam.workers_policy).Statement :
      s.Effect == "Deny" ||
      length(setintersection(toset(s.Action), toset(["s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"]))) == 0 ||
      alltrue([for r in s.Resource : r == "arn:aws:s3:::example-output-bucket/philter-batch/*"])
    ])
    error_message = "The workers may write outside the output prefix: ${module.iam.workers_policy}"
  }
  assert {
    condition = contains([
      for s in jsondecode(module.iam.workers_policy).Statement : s.Sid
      if s.Effect == "Allow" && contains(s.Action, "s3:GetObject") && contains(s.Resource, "arn:aws:s3:::example-input-bucket/documents/*")
    ], "ReadInput")
    error_message = "The workers cannot read the input."
  }
  assert {
    condition = anytrue([
      for s in jsondecode(module.iam.workers_policy).Statement :
      s.Effect == "Deny" && contains(s.Action, "s3:PutObject") && contains(s.Action, "s3:DeleteObject") && s.Resource == ["arn:aws:s3:::example-input-bucket/documents/*"]
    ])
    error_message = "The workers' policy lacks the explicit deny of writes to the input."
  }
  assert {
    condition = one([
      for s in jsondecode(module.iam.workers_policy).Statement : s.Condition.StringLike["s3:prefix"]
      if s.Sid == "ListInput"
    ]) == ["documents/*"]
    error_message = "The workers may list outside the input prefix."
  }
  # Console: nothing on the input, and no delete.
  assert {
    condition = alltrue([
      for s in jsondecode(module.iam.console_policy).Statement :
      s.Effect == "Deny" || !anytrue([for r in s.Resource : startswith(r, "arn:aws:s3:::example-input-bucket")])
    ])
    error_message = "The console is allowed something on the input: ${module.iam.console_policy}"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(module.iam.console_policy).Statement :
      s.Effect == "Deny" && s.Action == ["s3:*"] && s.Resource == ["arn:aws:s3:::example-input-bucket/documents/*"]
    ])
    error_message = "The console's policy lacks the explicit deny on the input."
  }
  assert {
    condition = alltrue([
      for s in jsondecode(module.iam.console_policy).Statement :
      s.Effect == "Deny" || length(setintersection(toset(s.Action), toset(["s3:DeleteObject", "s3:*"]))) == 0
    ])
    error_message = "The console may delete objects."
  }
  # No KMS statements unless keys are given.
  assert {
    condition     = !strcontains(module.iam.workers_policy, "kms:")
    error_message = "KMS permissions without KMS keys."
  }
}

run "iam_kms" {
  command = plan
  variables {
    input_kms_key_arns  = ["arn:aws:kms:us-east-1:111122223333:key/11111111-1111-1111-1111-111111111111"]
    output_kms_key_arns = ["arn:aws:kms:us-east-1:111122223333:key/22222222-2222-2222-2222-222222222222"]
  }
  assert {
    condition = one([for s in jsondecode(module.iam.workers_policy).Statement : s if s.Sid == "InputKms"]) == {
      Sid       = "InputKms"
      Effect    = "Allow"
      Action    = ["kms:Decrypt"]
      Resource  = ["arn:aws:kms:us-east-1:111122223333:key/11111111-1111-1111-1111-111111111111"]
      Condition = { StringEquals = { "kms:ViaService" = "s3.us-east-1.amazonaws.com" } }
    }
    error_message = "Unexpected input KMS statement."
  }
  assert {
    condition     = !strcontains(module.iam.console_policy, "11111111-1111-1111-1111-111111111111")
    error_message = "The console may use the input's KMS key."
  }
}

run "node_groups_match_chart_values" {
  command = plan

  assert {
    condition     = module.eks.node_groups.workers.capacity_type == "SPOT"
    error_message = "Workers should run on Spot by default."
  }
  assert {
    condition     = module.eks.node_groups.system.capacity_type == "ON_DEMAND"
    error_message = "The system nodes must be on demand."
  }
  # Workers are scheduled onto the worker nodes and tolerate exactly their taint.
  assert {
    condition = alltrue([
      for k, v in output.philter_batch_values.workers.nodeSelector : lookup(module.eks.node_groups.workers.labels, k, null) == v
    ])
    error_message = "The chart's workers.nodeSelector does not match the worker nodes' labels."
  }
  assert {
    condition = [for t in output.philter_batch_values.workers.tolerations : {
      key = t.key, value = t.value, effect = { NoSchedule = "NO_SCHEDULE", PreferNoSchedule = "PREFER_NO_SCHEDULE", NoExecute = "NO_EXECUTE" }[t.effect]
    }] == module.eks.node_groups.workers.taints
    error_message = "The chart's workers.tolerations do not match the worker nodes' taints."
  }
  # The console and the small steps land on the system nodes, which have no taint.
  assert {
    condition = alltrue([
      for k, v in merge(output.philter_batch_values.console.nodeSelector, output.philter_batch_values.steps.nodeSelector) :
      lookup(module.eks.node_groups.system.labels, k, null) == v
    ])
    error_message = "The console's or steps' nodeSelector does not match the system nodes' labels."
  }
  assert {
    condition     = length(module.eks.node_groups.system.taints) == 0
    error_message = "The system nodes must not be tainted."
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).controller.nodeSelector == output.philter_batch_values.steps.nodeSelector
    error_message = "Argo's controller must run on the system nodes."
  }
}

run "network_policy_and_images" {
  command = plan

  assert {
    condition     = jsondecode(module.eks.vpc_cni_configuration).enableNetworkPolicy == "true"
    error_message = "The VPC CNI must enforce NetworkPolicy."
  }
  assert {
    condition     = output.philter_batch_values.networkPolicy.objectStore.to == [{ ipBlock = { cidr = "198.51.100.0/24" } }, { ipBlock = { cidr = "203.0.113.0/24" } }]
    error_message = "The chart's object store egress must be the S3 prefix list's CIDRs."
  }
  assert {
    condition     = output.philter_batch_values.networkPolicy.kubernetesApi.to == [{ ipBlock = { cidr = "10.80.0.0/20" } }, { ipBlock = { cidr = "10.80.16.0/20" } }, { ipBlock = { cidr = "172.20.0.1/32" } }]
    error_message = "The chart's API egress must be the cluster subnets and the kubernetes Service IP."
  }
  assert {
    condition     = output.philter_batch_values.networkPolicy.extraEgress[0].to[0].ipBlock.cidr == "169.254.170.23/32"
    error_message = "Pods must reach the Pod Identity agent."
  }
  assert {
    condition     = output.philter_batch_values.image == { registry = "111122223333.dkr.ecr.us-east-1.amazonaws.com", digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111" }
    error_message = "Unexpected philter-batch image values: ${jsonencode(output.philter_batch_values.image)}"
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).controller.image == { registry = "111122223333.dkr.ecr.us-east-1.amazonaws.com", repository = "argoproj/workflow-controller", tag = "v4.1.6@sha256:a012a639ead374a4a9306093d6b29dfdc7e6c4c1a85c684582cb961a6cc8bcf3" }
    error_message = "Argo's controller image must come from images.yaml, by digest, from the customer's registry."
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).crds.upgradeJob.image == { registry = "111122223333.dkr.ecr.us-east-1.amazonaws.com", repository = "argoproj/argo-workflows-crdinstaller", tag = "v4.1.6@sha256:b9be70ef3f4a0709d771e9c098a99fd2d97be1b095c1b160c668a2fde61dc237" }
    error_message = "Argo's CRD installer image must come from the customer's registry, by digest."
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).controller.workflowRestrictions.templateReferencing == "Strict"
    error_message = "Argo must run only workflows that reference a WorkflowTemplate."
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).server.enabled == false
    error_message = "Argo's server must be off."
  }
}

run "private_by_default" {
  command = plan

  assert {
    condition     = module.network.route_table_routes != null && length(module.network.route_table_routes) == 0
    error_message = "The new VPC's route table must hold no route to a NAT or internet gateway."
  }
  assert {
    condition     = module.eks.endpoint_access == { private = true, public = false }
    error_message = "The cluster endpoint must be private only by default."
  }
  assert {
    condition     = output.kubeconfig_commands[0] == "aws eks update-kubeconfig --region us-east-1 --name philter-batch"
    error_message = "Unexpected kubeconfig command: ${output.kubeconfig_commands[0]}"
  }
  assert {
    condition     = output.console_commands[2] == "kubectl exec -n philter-batch philter-batch-console-0 -- philter-batch submit <run_id>"
    error_message = "Unexpected console command: ${output.console_commands[2]}"
  }
}

run "public_endpoint_when_cidrs_given" {
  command = plan
  variables {
    cluster_public_access_cidrs = ["192.0.2.10/32"]
    access_host_enabled         = false
  }
  assert {
    condition     = module.eks.endpoint_access == { private = true, public = true }
    error_message = "A public endpoint was asked for."
  }
}

run "output_overlapping_input_is_refused" {
  command = plan
  variables {
    output_bucket = "example-input-bucket"
    output_prefix = "documents/out/"
  }
  expect_failures = [terraform_data.input_checks]
}

run "digest_differing_from_images_yaml_is_refused" {
  command = plan
  variables {
    image_digests = {
      "philter-batch" = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      "argoexec"      = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
    }
  }
  expect_failures = [terraform_data.input_checks]
}

run "missing_philter_batch_digest_is_refused" {
  command = plan
  variables {
    image_digests = {}
  }
  expect_failures = [terraform_data.input_checks]
}

run "existing_vpc" {
  command = plan
  variables {
    create_vpc      = false
    vpc_id          = "vpc-0123456789abcdef0"
    subnet_ids      = ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1"]
    route_table_ids = ["rtb-0123456789abcdef0"]
  }
  override_data {
    target = module.network.data.aws_vpc.existing[0]
    values = { cidr_block = "10.90.0.0/16" }
  }
  override_data {
    target = module.network.data.aws_subnet.existing["subnet-0123456789abcdef0"]
    values = { cidr_block = "10.90.0.0/20" }
  }
  override_data {
    target = module.network.data.aws_subnet.existing["subnet-0123456789abcdef1"]
    values = { cidr_block = "10.90.16.0/20" }
  }
  assert {
    condition     = module.network.vpc_id == "vpc-0123456789abcdef0" && module.network.route_table_routes == null
    error_message = "An existing VPC must be used as given."
  }
  assert {
    condition     = output.philter_batch_values.networkPolicy.kubernetesApi.to == [{ ipBlock = { cidr = "10.90.0.0/20" } }, { ipBlock = { cidr = "10.90.16.0/20" } }, { ipBlock = { cidr = "172.20.0.1/32" } }]
    error_message = "The chart's API egress must be the given subnets."
  }
}

run "first_apply_without_software" {
  command = plan
  variables {
    install_software = false
  }
  assert {
    condition     = length(module.software) == 0
    error_message = "install_software = false must install nothing with Helm."
  }
}

run "no_internet_by_default" {
  command = plan
  assert {
    condition     = length(module.network.nat_gateway_ids) == 0
    error_message = "No NAT gateway unless internet_access is set."
  }
  assert {
    condition     = output.ecr_registry == "111122223333.dkr.ecr.us-east-1.amazonaws.com" && toset(values(output.image_registries)) == toset(["111122223333.dkr.ecr.us-east-1.amazonaws.com"])
    error_message = "By default every image comes from the account's ECR: ${jsonencode(output.image_registries)}"
  }
}

run "internet_access" {
  command = plan
  variables {
    internet_access = true
  }
  assert {
    condition     = length(module.network.nat_gateway_ids) == 1
    error_message = "internet_access must create one NAT gateway."
  }
  assert {
    condition     = [for r in module.network.route_table_routes : r.cidr_block] == ["0.0.0.0/0"]
    error_message = "The private route table must hold only the default route to the NAT gateway: ${jsonencode(module.network.route_table_routes)}"
  }
  assert {
    condition     = output.interface_endpoints == sort(["ec2", "eks", "eks-auth", "ssm", "ssmmessages", "ec2messages"])
    error_message = "With internet_access, ECR's endpoints are not needed: ${jsonencode(output.interface_endpoints)}"
  }
  assert {
    condition     = [for s in jsondecode(module.network.s3_endpoint.policy).Statement : s.Sid] == ["ReadInput", "ReadWriteOutput"]
    error_message = "With internet_access, the S3 endpoint policy must not allow ECR's layer bucket."
  }
  assert {
    condition     = length(data.aws_ecr_image.images) == 0
    error_message = "With internet_access, no image is looked up in ECR."
  }
  assert {
    condition     = output.ecr_registry == null
    error_message = "With internet_access, there is no ECR registry."
  }
  assert {
    condition     = output.philter_batch_values.image == { registry = "docker.io", repository = "philterd/philter-batch", digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111" }
    error_message = "Unexpected philter-batch image values: ${jsonencode(output.philter_batch_values.image)}"
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).controller.image == { registry = "quay.io", repository = "argoproj/workflow-controller", tag = "v4.1.6@sha256:a012a639ead374a4a9306093d6b29dfdc7e6c4c1a85c684582cb961a6cc8bcf3" }
    error_message = "Argo's controller image must come from quay.io, by digest."
  }
  assert {
    condition     = yamldecode(module.software[0].argo_values).executor.image.registry == "quay.io" && yamldecode(module.software[0].argo_values).crds.upgradeJob.image.registry == "quay.io"
    error_message = "Argo's executor and CRD installer images must come from quay.io."
  }
  # The redaction pods stay restricted: the chart's NetworkPolicy is the same as without internet access.
  assert {
    condition     = output.philter_batch_values.networkPolicy.enabled && output.philter_batch_values.networkPolicy.objectStore.to == [{ ipBlock = { cidr = "198.51.100.0/24" } }, { ipBlock = { cidr = "203.0.113.0/24" } }]
    error_message = "The NetworkPolicy must still allow only the object store, DNS, the API, and Pod Identity."
  }
}

run "internet_access_existing_vpc" {
  command = plan
  variables {
    internet_access = true
    create_vpc      = false
    vpc_id          = "vpc-0123456789abcdef0"
    subnet_ids      = ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1"]
    route_table_ids = ["rtb-0123456789abcdef0"]
  }
  override_data {
    target = module.network.data.aws_vpc.existing[0]
    values = { cidr_block = "10.90.0.0/16" }
  }
  override_data {
    target = module.network.data.aws_subnet.existing["subnet-0123456789abcdef0"]
    values = { cidr_block = "10.90.0.0/20" }
  }
  override_data {
    target = module.network.data.aws_subnet.existing["subnet-0123456789abcdef1"]
    values = { cidr_block = "10.90.16.0/20" }
  }
  assert {
    condition     = length(module.network.nat_gateway_ids) == 0
    error_message = "An existing VPC's routing is its own: no NAT gateway is created in it."
  }
  assert {
    condition     = output.philter_batch_values.image.registry == "docker.io"
    error_message = "With internet_access, the images come from public registries in an existing VPC too."
  }
}
