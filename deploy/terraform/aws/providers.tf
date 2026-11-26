provider "aws" {
  region = var.region

  default_tags {
    tags = merge(var.tags, { "philterd.ai/stack" = var.name })
  }
}

# The Helm provider authenticates as the operator (aws eks get-token) and reaches the API at the cluster's endpoint,
# or at kubernetes_api_server (such as the access host's port forward) with the endpoint's name for TLS.
provider "helm" {
  kubernetes = {
    host                   = local.api_server
    tls_server_name        = var.kubernetes_api_server == null ? null : local.endpoint_host
    cluster_ca_certificate = base64decode(module.eks.cluster_ca_certificate)
    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name, "--region", var.region]
    }
  }
}
