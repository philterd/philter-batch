# An output bucket created by the stack. It is kept on destroy: prevent_destroy makes a plan that would delete it fail,
# so `terraform destroy` refuses until the bucket is removed from the state (docs/aws.md, Destroy).

variable "bucket" {
  description = "The bucket's name."
  type        = string
}

variable "kms_key_arn" {
  description = "A KMS key for SSE-KMS. Null: SSE-S3."
  type        = string
  default     = null
}

variable "noncurrent_version_days" {
  description = "Days after which noncurrent object versions expire. Workers overwrite their claims and progress objects many times, and finalize deletes staged objects, so versions accumulate."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags."
  type        = map(string)
  default     = {}
}

# Server access logging needs a log bucket, which is the customer's choice.
#trivy:ignore:AVD-AWS-0089
resource "aws_s3_bucket" "this" {
  bucket = var.bucket
  tags   = var.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Versioning keeps a deleted or overwritten object recoverable for noncurrent_version_days. S3's conditional writes,
# which the run's coordination uses, apply to the current version in a versioned bucket.
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "noncurrent-versions-and-incomplete-uploads"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn == null ? "AES256" : "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.this]
}

output "bucket" {
  description = "The bucket's name."
  value       = aws_s3_bucket.this.id
}
