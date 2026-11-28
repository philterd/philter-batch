# The pseudonymization secret, created by the stack: one random value from which Philter Batch derives its keys
# (docs/configuration.md). The value comes from an ephemeral resource and is written through secret_string_wo, so it
# is in neither the plan nor the state. The secret is kept on destroy: prevent_destroy makes a plan that would delete
# it fail, so `terraform destroy` refuses until it is removed from the state (docs/aws.md, Destroy). It has no
# rotation: a new value would give later runs other keys.

variable "name" {
  description = "The secret's name."
  type        = string
}

variable "kms_key_arn" {
  description = "A customer managed KMS key to encrypt the secret with. Null: Secrets Manager's AWS managed key."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags."
  type        = map(string)
  default     = {}
}

# 64 letters and digits, about 381 bits, generated locally by the random provider (with crypto/rand).
ephemeral "random_password" "value" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "this" {
  name                    = var.name
  description             = "Philter Batch pseudonymization secret: the keys of FPE_ENCRYPT_REPLACE and CRYPTO_REPLACE are derived from it. Deleting it makes FPE values irreversible. Do not rotate it."
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 30
  tags                    = var.tags

  lifecycle {
    prevent_destroy = true
  }
}

# Written once: secret_string_wo_version stays 1, so later applies do not write a new value.
resource "aws_secretsmanager_secret_version" "this" {
  secret_id                = aws_secretsmanager_secret.this.id
  secret_string_wo         = ephemeral.random_password.value.result
  secret_string_wo_version = 1
}

output "arn" {
  description = "The secret's ARN."
  value       = aws_secretsmanager_secret.this.arn
}
