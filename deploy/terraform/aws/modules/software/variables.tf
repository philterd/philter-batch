variable "argo_namespace" {
  description = "Argo Workflows' namespace."
  type        = string
}

variable "argo_chart_version" {
  description = "The argo-workflows chart's version."
  type        = string
}

variable "argo_values" {
  description = "Values for the argo-workflows chart, as YAML."
  type        = string
}

variable "namespace" {
  description = "Philter Batch's namespace."
  type        = string
}

variable "release_name" {
  description = "The Philter Batch release's name."
  type        = string
}

variable "chart_path" {
  description = "The Philter Batch chart's directory."
  type        = string
}

variable "chart_values" {
  description = "Values for the Philter Batch chart, as YAML documents, later ones winning."
  type        = list(string)
}

variable "depends_on_ready" {
  description = "Anything the releases must wait for (the cluster's access, nodes, add-ons, and Pod Identity associations). Only its presence matters."
  type        = any
}

variable "guard" {
  description = "What the destroy-time check of running workflows needs: the cluster, region, an optional API server address, and whether to skip it."
  type = object({
    cluster_name = string
    region       = string
    server       = string
    skip         = bool
  })
}
