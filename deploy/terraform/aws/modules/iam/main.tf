# The two identities of a Philter Batch deployment, each bound to its service account through EKS Pod Identity:
#
# - workers (every pod of the workflow): read on the input; read, write, and delete under the output prefix.
# - console: read and write under the output prefix (configure, set-policy, approve, reject, status,
#   sample-report). Nothing on the input: an explicit deny keeps it so whatever else is attached.
#
# s3:ListBucket on the output bucket is not limited to the prefix: the store reads a missing object as a 404, which
# S3 returns only to a caller with s3:ListBucket on the bucket, and takes the store's clock from HeadBucket, which
# s3:ListBucket authorizes; neither request carries an s3:prefix for a condition to match.

locals {
  input_bucket_arn  = "arn:aws:s3:::${var.input_bucket}"
  output_bucket_arn = "arn:aws:s3:::${var.output_bucket}"
  input_objects     = "${local.input_bucket_arn}/${var.input_prefix}*"
  output_objects    = "${local.output_bucket_arn}/${var.output_prefix}*"
  kms_condition     = { StringEquals = { "kms:ViaService" = "s3.${var.region}.amazonaws.com" } }

  output_kms_statements = length(var.output_kms_key_arns) == 0 ? [] : [{
    Sid       = "OutputKms"
    Effect    = "Allow"
    Action    = ["kms:Decrypt", "kms:GenerateDataKey"]
    Resource  = var.output_kms_key_arns
    Condition = local.kms_condition
  }]

  # Explicit denies on the input's objects. They never reach the output: the root module refuses an output that
  # overlaps the input.
  deny_input_write = {
    Sid      = "DenyInputWrite"
    Effect   = "Deny"
    Action   = ["s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion", "s3:PutObjectAcl", "s3:PutObjectTagging", "s3:RestoreObject"]
    Resource = [local.input_objects]
  }

  workers_policy = {
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "ListInput"
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = [local.input_bucket_arn]
        Condition = { StringLike = { "s3:prefix" = ["${var.input_prefix}*"] } }
      },
      {
        Sid      = "ReadInput"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = [local.input_objects]
      },
      {
        Sid      = "ListOutput"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [local.output_bucket_arn]
      },
      {
        Sid      = "WriteOutput"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource = [local.output_objects]
      },
      local.deny_input_write,
      ], length(var.input_kms_key_arns) == 0 ? [] : [{
        Sid       = "InputKms"
        Effect    = "Allow"
        Action    = ["kms:Decrypt"]
        Resource  = var.input_kms_key_arns
        Condition = local.kms_condition
    }], local.output_kms_statements)
  }

  console_policy = {
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "ListOutput"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [local.output_bucket_arn]
      },
      {
        Sid      = "ReadWriteOutput"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = [local.output_objects]
      },
      {
        Sid      = "DenyInput"
        Effect   = "Deny"
        Action   = ["s3:*"]
        Resource = [local.input_objects]
      },
    ], local.output_kms_statements)
  }
}

# The trust policy AWS documents for EKS Pod Identity, limited by the session tags EKS sets to one namespace and
# service account (https://docs.aws.amazon.com/eks/latest/userguide/pod-id-role.html).
resource "aws_iam_role" "workers" {
  name = "${var.name}-workers"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Condition = {
        StringEquals = {
          "aws:RequestTag/kubernetes-namespace"       = [var.namespace]
          "aws:RequestTag/kubernetes-service-account" = [var.workers_service_account]
        }
      }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy" "workers" {
  name   = "philter-batch-workers"
  role   = aws_iam_role.workers.id
  policy = jsonencode(local.workers_policy)
}

resource "aws_iam_role" "console" {
  name = "${var.name}-console"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Condition = {
        StringEquals = {
          "aws:RequestTag/kubernetes-namespace"       = [var.namespace]
          "aws:RequestTag/kubernetes-service-account" = [var.console_service_account]
        }
      }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy" "console" {
  name   = "philter-batch-console"
  role   = aws_iam_role.console.id
  policy = jsonencode(local.console_policy)
}

resource "aws_eks_pod_identity_association" "workers" {
  cluster_name    = var.cluster_name
  namespace       = var.namespace
  service_account = var.workers_service_account
  role_arn        = aws_iam_role.workers.arn
  tags            = var.tags
}

resource "aws_eks_pod_identity_association" "console" {
  cluster_name    = var.cluster_name
  namespace       = var.namespace
  service_account = var.console_service_account
  role_arn        = aws_iam_role.console.arn
  tags            = var.tags
}
