# Creates the S3 bucket that holds the stack's Terraform state, once per customer account, before the first
# `terraform init` of the stack. It is separate from the stack because a stack cannot store its state in a bucket it
# creates and destroys. Its own state is local and small; the bucket is deleted by hand at the end of the
# engagement (docs/aws.md).

terraform {
  required_version = ">= 1.16.0, < 1.17.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.74.0"
    }
  }
}

variable "region" {
  description = "The region of the bucket."
  type        = string
}

variable "bucket" {
  description = "The state bucket's name."
  type        = string
}

variable "kms_key_arn" {
  description = "A KMS key for SSE-KMS. Null: SSE-S3."
  type        = string
  default     = null
}

provider "aws" {
  region = var.region
}

# Server access logging needs a log bucket, which is the customer's choice.
#trivy:ignore:AVD-AWS-0089
resource "aws_s3_bucket" "state" {
  bucket = var.bucket

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Versioning keeps earlier states, so a bad write can be rolled back.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
  depends_on = [aws_s3_bucket_versioning.state]
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn == null ? "AES256" : "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.state]
}

output "backend_config" {
  description = "The contents of backend.hcl for the stack."
  value       = <<-EOT
    bucket       = "${aws_s3_bucket.state.id}"
    key          = "philter-batch/terraform.tfstate"
    region       = "${var.region}"
    encrypt      = true
    use_lockfile = true
  EOT
}
