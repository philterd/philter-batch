# The created pseudonymization secret is kept on destroy. Expected to FAIL, as output_bucket.tftest.hcl is: the second
# run plans the secret's deletion, which prevent_destroy refuses, and so does the teardown.
# scripts/test-prevent-destroy.sh runs this file and passes only when Terraform refuses both.

mock_provider "aws" {
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:example-pseudonymization-AbCdEf" }
  }
}

run "create" {
  module {
    source = "./tests/prevent_destroy/wrapper"
  }
  variables {
    create_pseudonymization_secret = true
  }
}

run "turn_off_create_pseudonymization_secret" {
  command = plan
  module {
    source = "./tests/prevent_destroy/wrapper"
  }
  variables {
    create_pseudonymization_secret = false
  }
}
