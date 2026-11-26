output "workers_role_arn" {
  description = "The workers' role."
  value       = aws_iam_role.workers.arn
}

output "console_role_arn" {
  description = "The console's role."
  value       = aws_iam_role.console.arn
}

output "workers_policy" {
  description = "The workers' permissions, as JSON."
  value       = jsonencode(local.workers_policy)
}

output "console_policy" {
  description = "The console's permissions, as JSON."
  value       = jsonencode(local.console_policy)
}

output "pod_identity_association_ids" {
  description = "The Pod Identity associations, so that the chart is installed after them."
  value       = [aws_eks_pod_identity_association.workers.association_id, aws_eks_pod_identity_association.console.association_id]
}
