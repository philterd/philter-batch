locals {
  console = "${local.release_name}-console-0"
  exec    = "kubectl exec -n ${var.namespace} ${local.console} --"
  exec_i  = "kubectl exec -i -n ${var.namespace} ${local.console} --"
  tunnel = var.access_host_enabled ? join(" ", [
    "aws ssm start-session --region ${var.region} --target ${module.eks.access_host_id}",
    "--document-name AWS-StartPortForwardingSessionToRemoteHost",
    "--parameters '{\"host\":[\"${local.endpoint_host}\"],\"portNumber\":[\"443\"],\"localPortNumber\":[\"8443\"]}'",
  ]) : null
}

output "cluster_name" {
  description = "The EKS cluster."
  value       = module.eks.cluster_name
}

output "ecr_registry" {
  description = "The registry the images are pulled from."
  value       = local.registry
}

output "workers_role_arn" {
  description = "The workers' IAM role (read on the input, write under the output prefix)."
  value       = module.iam.workers_role_arn
}

output "console_role_arn" {
  description = "The console's IAM role (the output prefix only)."
  value       = module.iam.console_role_arn
}

output "interface_endpoints" {
  description = "The interface VPC endpoints created."
  value       = module.network.interface_endpoint_services
}

output "access_host_tunnel" {
  description = "Run in its own terminal: forwards localhost:8443 to the cluster's private endpoint through the access host."
  value       = local.tunnel
}

output "kubeconfig_commands" {
  description = "Writes a kubeconfig entry for the cluster; through the access host, points it at the forwarded port."
  value = concat(
    ["aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"],
    var.access_host_enabled ? ["kubectl config set-cluster ${module.eks.cluster_arn} --server=https://127.0.0.1:8443 --tls-server-name=${local.endpoint_host}"] : []
  )
}

output "console_commands" {
  description = "The console steps, one kubectl exec line each (docs/kubernetes.md)."
  value = [
    "${local.exec_i} philter-batch configure --stdin < run.yaml",
    "${local.exec_i} philter-batch set-policy <run_id> --stdin < policy.json",
    "${local.exec} philter-batch submit <run_id>",
    "${local.exec} philter-batch status <run_id>",
    "${local.exec} philter-batch sample-report <run_id> > sample-report.html",
    "${local.exec} philter-batch approve <run_id> sample --by \"<name>\"",
    "${local.exec} philter-batch approve <run_id> release --by \"<name>\"",
  ]
}

output "destroy_check" {
  description = "Run before terraform destroy: fails while a workflow is running. Destroy runs it too."
  value       = "scripts/check-no-running-workflows.sh --cluster ${module.eks.cluster_name} --region ${var.region} --namespace ${var.namespace}${var.kubernetes_api_server == null ? "" : " --server ${var.kubernetes_api_server}"}"
}

output "philter_batch_values" {
  description = "The values passed to the Philter Batch chart."
  value       = local.philter_batch_values
}
