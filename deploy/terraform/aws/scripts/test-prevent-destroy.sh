#!/usr/bin/env bash
# Checks that what the stack creates and must keep is kept: the output bucket and the pseudonymization secret. Each file
# in tests/prevent_destroy plans one's deletion against a mocked AWS provider and must be refused with "Instance
# cannot be destroyed". Passes only on those refusals. No AWS calls.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

terraform init -backend=false -input=false -test-directory=tests/prevent_destroy >/dev/null
status=0
for check in "output_bucket.tftest.hcl|module.output_bucket\[0\].aws_s3_bucket.this has|output bucket" \
    "pseudonymization_secret.tftest.hcl|module.pseudonymization_secret\[0\].aws_secretsmanager_secret.this has|pseudonymization secret"; do
  IFS='|' read -r file address what <<<"$check"
  if out="$(terraform test -no-color -test-directory=tests/prevent_destroy -filter="tests/prevent_destroy/$file" 2>&1)"; then
    printf '%s\n' "$out"
    printf 'FAIL: planning the deletion of the created %s succeeded; prevent_destroy is not in effect.\n' "$what" >&2
    status=1
  elif printf '%s\n' "$out" | grep -q 'Instance cannot be destroyed' && printf '%s\n' "$out" | grep -q "$address"; then
    printf 'PASS: Terraform refuses to plan the deletion of the created %s.\n' "$what"
  else
    printf '%s\n' "$out"
    printf 'FAIL: the %s test failed, but not with the prevent_destroy refusal.\n' "$what" >&2
    status=1
  fi
done
exit "$status"
