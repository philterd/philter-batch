# State is kept in an S3 bucket in the customer's account, created once with state-bucket/ (never by this stack).
# The bucket, key, and region are given at init: terraform init -backend-config=backend.hcl (docs/aws.md).
terraform {
  backend "s3" {}
}
