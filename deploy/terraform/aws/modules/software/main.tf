# Argo Workflows (without its server) and Philter Batch. Neither creates AWS resources: no LoadBalancer services, no
# ingress, and no persistent volumes. Runs are started from the console, never by an apply.

resource "terraform_data" "ready" {
  input = var.depends_on_ready
}

resource "helm_release" "argo_workflows" {
  name             = "argo-workflows"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-workflows"
  version          = var.argo_chart_version
  namespace        = var.argo_namespace
  create_namespace = true
  values           = [var.argo_values]
  wait             = true
  timeout          = 600

  depends_on = [terraform_data.ready]
}

resource "helm_release" "philter_batch" {
  name             = var.release_name
  chart            = var.chart_path
  namespace        = var.namespace
  create_namespace = true
  values           = var.chart_values
  wait             = true
  timeout          = 600

  depends_on = [helm_release.argo_workflows]
}

# Destroyed first, before the releases: refuses the destroy while a workflow in the namespace is running (or waiting
# at a gate). The check is scripts/check-no-running-workflows.sh, which the operator can also run by hand.
resource "terraform_data" "workflow_guard" {
  input = {
    script       = "${path.root}/scripts/check-no-running-workflows.sh"
    cluster_name = var.guard.cluster_name
    region       = var.guard.region
    namespace    = var.namespace
    server       = var.guard.server
    skip         = var.guard.skip
  }

  provisioner "local-exec" {
    when    = destroy
    command = self.input.skip ? "echo 'Skipping the check for running workflows (allow_destroy_while_running).'" : "${self.input.script} --cluster ${self.input.cluster_name} --region ${self.input.region} --namespace ${self.input.namespace}${self.input.server == "" ? "" : " --server ${self.input.server}"}"
  }

  depends_on = [helm_release.philter_batch]
}
