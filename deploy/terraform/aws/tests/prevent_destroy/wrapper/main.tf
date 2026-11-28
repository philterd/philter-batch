# The output bucket and pseudonymization secret modules as the root module uses them: each behind a count on its
# create_ variable.
terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0, < 7.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.7.2, < 4.0.0"
    }
  }
}

variable "create_output_bucket" {
  description = "As in the root module."
  type        = bool
  default     = false
}

variable "create_pseudonymization_secret" {
  description = "As in the root module."
  type        = bool
  default     = false
}

module "pseudonymization_secret" {
  source = "../../../modules/pseudonymization-secret"
  count  = var.create_pseudonymization_secret ? 1 : 0
  name   = "example-pseudonymization"
}

module "output_bucket" {
  source = "../../../modules/output-bucket"
  count  = var.create_output_bucket ? 1 : 0
  bucket = "example-output-bucket"
}
