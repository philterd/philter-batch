locals {
  public_endpoint = length(var.public_access_cidrs) > 0
  addons_before_nodes = {
    "vpc-cni"                = jsonencode({ enableNetworkPolicy = "true" }) # so the chart's NetworkPolicy is enforced
    "kube-proxy"             = null
    "eks-pod-identity-agent" = null
  }
}

# --- The cluster -----------------------------------------------------------------------------------------------------

resource "aws_iam_role" "cluster" {
  name = "${var.name}-cluster"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# Created here, before the cluster, so that EKS does not create it and leave it behind after a destroy.
# Control plane logs hold no document content; CloudWatch Logs encrypts them at rest without a customer key.
#trivy:ignore:AVD-AWS-0017
resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${var.name}/cluster"
  retention_in_days = var.cluster_log_retention_days
  tags              = var.tags
}

# Further rules for the cluster's endpoint: HTTPS from the access host.
resource "aws_security_group" "cluster" {
  name        = "${var.name}-cluster"
  description = "Additional rules for the EKS cluster endpoint"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-cluster" })
}

# Secrets: EKS envelope-encrypts all Kubernetes API data by default from 1.28, with an AWS owned key
# (https://docs.aws.amazon.com/eks/latest/userguide/envelope-encryption.html); secrets_kms_key_arn adds a customer key.
#trivy:ignore:AWS-0039
resource "aws_eks_cluster" "this" {
  name                          = var.name
  version                       = var.kubernetes_version
  role_arn                      = aws_iam_role.cluster.arn
  enabled_cluster_log_types     = var.cluster_log_types
  bootstrap_self_managed_addons = false

  vpc_config {
    subnet_ids              = var.subnet_ids
    security_group_ids      = [aws_security_group.cluster.id]
    endpoint_private_access = true
    endpoint_public_access  = local.public_endpoint
    public_access_cidrs     = local.public_endpoint ? var.public_access_cidrs : null
  }

  kubernetes_network_config {
    service_ipv4_cidr = var.service_cidr
  }

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  # Without a customer key, EKS envelope-encrypts all Kubernetes API data with an AWS owned key (1.28 and later).
  dynamic "encryption_config" {
    for_each = var.secrets_kms_key_arn == null ? [] : [var.secrets_kms_key_arn]
    content {
      resources = ["secrets"]
      provider {
        key_arn = encryption_config.value
      }
    }
  }

  # Standard support only, so a forgotten stack does not move to extended support pricing.
  upgrade_policy {
    support_type = "STANDARD"
  }

  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.cluster, aws_cloudwatch_log_group.cluster]
}

resource "aws_eks_access_entry" "admin" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = var.admin_principal_arn
  type          = "STANDARD"
  tags          = var.tags
}

resource "aws_eks_access_policy_association" "admin" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_eks_access_entry.admin.principal_arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
  access_scope {
    type = "cluster"
  }
}

# --- Add-ons ---------------------------------------------------------------------------------------------------------

data "aws_eks_addon_version" "this" {
  for_each           = setsubtract(toset(concat(keys(local.addons_before_nodes), ["coredns"])), toset(keys(var.addon_versions)))
  addon_name         = each.value
  kubernetes_version = aws_eks_cluster.this.version
  most_recent        = false
}

# The CNI, kube-proxy, and the Pod Identity agent are DaemonSets that nodes need to become ready, so they come first.
resource "aws_eks_addon" "before_nodes" {
  for_each                    = local.addons_before_nodes
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.key
  addon_version               = lookup(var.addon_versions, each.key, try(data.aws_eks_addon_version.this[each.key].version, null))
  configuration_values        = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags
}

# CoreDNS is a Deployment and needs nodes to become active.
resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "coredns"
  addon_version               = lookup(var.addon_versions, "coredns", try(data.aws_eks_addon_version.this["coredns"].version, null))
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = var.tags

  depends_on = [aws_eks_node_group.system]
}

# --- Nodes -----------------------------------------------------------------------------------------------------------

resource "aws_iam_role" "node" {
  name = "${var.name}-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = ["sts:AssumeRole"]
    }]
  })
  tags = var.tags
}

# The node role AWS documents (create-node-role.html), with the CNI's policy on it rather than on a role for the
# aws-node service account.
resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

# IMDSv2 only, with a hop limit of 1, so pods cannot reach the instance metadata service or the node's role: pods
# get credentials from EKS Pod Identity. Encrypted root volumes.
resource "aws_launch_template" "nodes" {
  for_each = {
    system  = var.system_nodes.disk_size_gib
    workers = var.worker_nodes.disk_size_gib
  }
  name_prefix            = "${var.name}-${each.key}-"
  update_default_version = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = each.value
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = merge(var.tags, { Name = "${var.name}-${each.key}" })
  }

  tags = var.tags
}

resource "aws_eks_node_group" "system" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name}-system"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.subnet_ids
  ami_type        = "AL2023_x86_64_STANDARD"
  capacity_type   = "ON_DEMAND"
  instance_types  = var.system_nodes.instance_types
  labels          = { (var.node_pool_label) = "system" }

  launch_template {
    id      = aws_launch_template.nodes["system"].id
    version = aws_launch_template.nodes["system"].latest_version
  }

  scaling_config {
    desired_size = var.system_nodes.desired_size
    min_size     = var.system_nodes.min_size
    max_size     = var.system_nodes.max_size
  }

  update_config {
    max_unavailable = 1
  }

  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.node, aws_eks_addon.before_nodes]
}

# Spot by default. EKS turns on Capacity Rebalancing for Spot managed node groups and drains a node that receives a
# rebalance recommendation or an interruption notice, so worker pods get SIGTERM and save their progress.
resource "aws_eks_node_group" "workers" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name}-workers"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.subnet_ids
  ami_type        = "AL2023_x86_64_STANDARD"
  capacity_type   = var.worker_nodes.capacity_type
  instance_types  = var.worker_nodes.instance_types
  labels          = { (var.node_pool_label) = "workers" }

  taint {
    key    = var.node_pool_label
    value  = "workers"
    effect = "NO_SCHEDULE"
  }

  launch_template {
    id      = aws_launch_template.nodes["workers"].id
    version = aws_launch_template.nodes["workers"].latest_version
  }

  scaling_config {
    desired_size = var.worker_nodes.desired_size
    min_size     = var.worker_nodes.min_size
    max_size     = var.worker_nodes.max_size
  }

  update_config {
    max_unavailable = 1
  }

  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.node, aws_eks_addon.before_nodes]
}

# --- The access host -------------------------------------------------------------------------------------------------
# No public address and no inbound rules. The operator runs AWS-StartPortForwardingSessionToRemoteHost through it to
# reach the cluster's private endpoint from their machine.

data "aws_ssm_parameter" "al2023" {
  count = var.access_host_enabled ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_iam_role" "access_host" {
  count = var.access_host_enabled ? 1 : 0
  name  = "${var.name}-access-host"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = ["sts:AssumeRole"]
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "access_host" {
  count      = var.access_host_enabled ? 1 : 0
  role       = aws_iam_role.access_host[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "access_host" {
  count = var.access_host_enabled ? 1 : 0
  name  = "${var.name}-access-host"
  role  = aws_iam_role.access_host[0].name
  tags  = var.tags
}

resource "aws_security_group" "access_host" {
  count       = var.access_host_enabled ? 1 : 0
  name        = "${var.name}-access-host"
  description = "SSM access host: no inbound; HTTPS to the VPC only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-access-host" })
}

resource "aws_vpc_security_group_egress_rule" "access_host_https" {
  count             = var.access_host_enabled ? 1 : 0
  security_group_id = aws_security_group.access_host[0].id
  description       = "HTTPS to the VPC endpoints and the cluster endpoint"
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "cluster_from_access_host" {
  count                        = var.access_host_enabled ? 1 : 0
  security_group_id            = aws_security_group.cluster.id
  description                  = "The Kubernetes API from the access host"
  referenced_security_group_id = aws_security_group.access_host[0].id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_instance" "access_host" {
  count                       = var.access_host_enabled ? 1 : 0
  ami                         = data.aws_ssm_parameter.al2023[0].insecure_value
  instance_type               = var.access_host_instance_type
  subnet_id                   = var.subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.access_host[0].id]
  iam_instance_profile        = aws_iam_instance_profile.access_host[0].name
  associate_public_ip_address = false
  ebs_optimized               = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
  }

  tags = merge(var.tags, { Name = "${var.name}-access-host" })

  lifecycle {
    ignore_changes = [ami] # a newer AMI is not a reason to replace the relay mid-run
  }
}
