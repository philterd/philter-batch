#!/usr/bin/env bash
# Checks that a created output bucket is kept: tests/prevent_destroy plans the bucket's deletion against a mocked AWS
# provider and must be refused with "Instance cannot be destroyed". Passes only on that refusal. No AWS calls.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

terraform init -backend=false -input=false -test-directory=tests/prevent_destroy >/dev/null
if out="$(terraform test -no-color -test-directory=tests/prevent_destroy 2>&1)"; then
  printf '%s\n' "$out"
  printf 'FAIL: planning the output bucket'"'"'s deletion succeeded; prevent_destroy is not in effect.\n' >&2
  exit 1
fi
if printf '%s\n' "$out" | grep -q 'Instance cannot be destroyed' \
    && printf '%s\n' "$out" | grep -q 'module.output_bucket\[0\].aws_s3_bucket.this has'; then
  printf 'PASS: Terraform refuses to plan the deletion of the created output bucket.\n'
else
  printf '%s\n' "$out"
  printf 'FAIL: the test failed, but not with the prevent_destroy refusal.\n' >&2
  exit 1
fi
