output "cluster_name" {
  description = "The cluster's name."
  value       = aws_eks_cluster.this.name
}

output "cluster_arn" {
  description = "The cluster's ARN, also its kubeconfig context name."
  value       = aws_eks_cluster.this.arn
}

output "cluster_endpoint" {
  description = "The cluster's API endpoint."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_ca_certificate" {
  description = "The cluster's CA certificate, base64-encoded."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "access_host_id" {
  description = "The access host's instance ID, or null."
  value       = try(aws_instance.access_host[0].id, null)
}

output "ready" {
  description = "Everything the chart needs before it is installed: the admin access policy, the nodes, and the add-ons."
  value = {
    admin   = aws_eks_access_policy_association.admin.id
    system  = aws_eks_node_group.system.id
    workers = aws_eks_node_group.workers.id
    coredns = aws_eks_addon.coredns.id
    addons  = [for a in aws_eks_addon.before_nodes : a.id]
    # The access host and its rule, so that a destroy uninstalls the releases before cutting the port forward.
    access_host = [for i in aws_instance.access_host : i.id]
    access_rule = [for r in aws_vpc_security_group_ingress_rule.cluster_from_access_host : r.id]
  }
}

output "kubernetes_service_ip" {
  description = "The ClusterIP of the kubernetes Service (the first address of the service CIDR)."
  value       = cidrhost(var.service_cidr, 1)
}

output "node_groups" {
  description = "Each node group's capacity type, labels, and taints, for checking against the chart's placement values."
  value = {
    for k, g in { system = aws_eks_node_group.system, workers = aws_eks_node_group.workers } : k => {
      capacity_type      = g.capacity_type
      labels             = g.labels
      taints             = [for t in g.taint : { key = t.key, value = t.value, effect = t.effect }]
    }
  }
}

output "vpc_cni_configuration" {
  description = "The VPC CNI add-on's configuration values."
  value       = aws_eks_addon.before_nodes["vpc-cni"].configuration_values
}

output "endpoint_access" {
  description = "Whether the cluster's endpoint is private and public."
  value = {
    private = aws_eks_cluster.this.vpc_config[0].endpoint_private_access
    public  = aws_eks_cluster.this.vpc_config[0].endpoint_public_access
  }
}
