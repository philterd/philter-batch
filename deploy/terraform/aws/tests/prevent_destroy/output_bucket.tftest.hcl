# The created output bucket is kept on destroy. Expected to FAIL: the second run plans the bucket's deletion (the
# variable turned off), which prevent_destroy refuses, and the test's teardown, a destroy, is refused the same way.
# scripts/test-prevent-destroy.sh runs this file and passes only when Terraform refuses both.

mock_provider "aws" {
  mock_resource "aws_s3_bucket_lifecycle_configuration" {
    defaults = { transition_default_minimum_object_size = "all_storage_classes_128K" }
  }
}

run "create" {
  module {
    source = "./tests/prevent_destroy/wrapper"
  }
  variables {
    create_output_bucket = true
  }
}

run "turn_off_create_output_bucket" {
  command = plan
  module {
    source = "./tests/prevent_destroy/wrapper"
  }
  variables {
    create_output_bucket = false
  }
}
