variable "name" {
  description = "Name prefix for the roles."
  type        = string
}

variable "cluster_name" {
  description = "The EKS cluster, for the Pod Identity associations."
  type        = string
}

variable "namespace" {
  description = "The Philter Batch chart's namespace."
  type        = string
}

variable "workers_service_account" {
  description = "The service account of the workflow's pods."
  type        = string
}

variable "console_service_account" {
  description = "The console's service account."
  type        = string
}

variable "input_bucket" {
  description = "The bucket holding the documents."
  type        = string
}

variable "input_prefix" {
  description = "The prefix under which the documents are. Empty for the whole bucket."
  type        = string
}

variable "output_bucket" {
  description = "The bucket runs write to."
  type        = string
}

variable "output_prefix" {
  description = "The prefix under which runs write."
  type        = string
}

variable "input_kms_key_arns" {
  description = "KMS keys the input is encrypted with (SSE-KMS)."
  type        = list(string)
  default     = []
}

variable "output_kms_key_arns" {
  description = "KMS keys the output bucket encrypts with (SSE-KMS)."
  type        = list(string)
  default     = []
}

variable "region" {
  description = "The AWS region, for the KMS condition."
  type        = string
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}

variable "pseudonymization_secret_arn" {
  description = "The pseudonymization secret the workers may read. Null: none."
  type        = string
  default     = null
}

variable "pseudonymization_kms_key_arn" {
  description = "The secret's customer managed KMS key, for kms:Decrypt through Secrets Manager. Null: the AWS managed key, which needs no grant."
  type        = string
  default     = null
}

variable "console_reads_pseudonymization_secret" {
  description = "Let the console read the secret too, for fpe-key."
  type        = bool
  default     = false
}
