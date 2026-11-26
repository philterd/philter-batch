terraform {
  # Tested with 1.16.1. Mocked tests (terraform test) need 1.7 or later.
  required_version = ">= 1.16.0, < 1.17.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.74.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "3.3.0"
    }
  }
}
