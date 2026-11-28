# Applies the pseudonymization secret module against a mocked AWS provider (no AWS account or credentials) and the
# real random provider, which runs locally, then checks that the generated value is in neither the state nor the
# outputs. Run from deploy/terraform/aws:
#   terraform init -backend=false -test-directory=tests/pseudonymization_secret
#   terraform test -test-directory=tests/pseudonymization_secret

mock_provider "aws" {
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:philter-batch-pseudonymization-AbCdEf" }
  }
}

run "created" {
  module {
    source = "./modules/pseudonymization-secret"
  }
  variables {
    name = "philter-batch-pseudonymization"
  }
  assert {
    condition     = aws_secretsmanager_secret_version.this.secret_string == null && aws_secretsmanager_secret_version.this.secret_string_wo == null
    error_message = "The secret's value must not be in the state."
  }
  assert {
    condition     = aws_secretsmanager_secret_version.this.secret_string_wo_version == 1
    error_message = "The value must be written once, through secret_string_wo."
  }
  assert {
    condition     = aws_secretsmanager_secret.this.recovery_window_in_days == 30 && aws_secretsmanager_secret.this.kms_key_id == null
    error_message = "Unexpected secret settings."
  }
  assert {
    condition     = output.arn == "arn:aws:secretsmanager:us-east-1:111122223333:secret:philter-batch-pseudonymization-AbCdEf"
    error_message = "The module outputs the secret's ARN."
  }
}
