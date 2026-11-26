# The output bucket module as the root module uses it: behind a count on create_output_bucket.
terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0, < 7.0.0"
    }
  }
}

variable "create_output_bucket" {
  description = "As in the root module."
  type        = bool
}

module "output_bucket" {
  source = "../../../modules/output-bucket"
  count  = var.create_output_bucket ? 1 : 0
  bucket = "example-output-bucket"
}
