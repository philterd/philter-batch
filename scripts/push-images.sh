#!/usr/bin/env bash
# Copies the images pinned in deploy/helm/philter-batch/images.yaml, by digest, into this AWS account's private ECR.
#
#   scripts/push-images.sh [region]
#
# The account is the one the AWS CLI's credentials belong to. The region defaults to AWS_REGION, AWS_DEFAULT_REGION,
# or the AWS CLI's configured region. Each missing
# repository is created with immutable tags, scan on push, and encryption. An image already there is skipped, so the
# script can be run again. An image with no digest in images.yaml is skipped with a note.
#
# PHILTER_BATCH_IMAGE=<image>@sha256:<digest> copies philter-batch from there instead, for example a build you pushed
# to your own registry before the image is published.
#
# Needs the AWS CLI and crane (https://github.com/google/go-containerregistry).
set -euo pipefail

images="$(dirname "$0")/../deploy/helm/philter-batch/images.yaml"
region="${1:-${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region || true)}}}"
[ -n "$region" ] || { echo "push-images: no region: give one, or set AWS_REGION" >&2; exit 1; }
account="$(aws sts get-caller-identity --query Account --output text)"
registry="$account.dkr.ecr.$region.amazonaws.com"

# The ECR login goes to a temporary configuration, removed on exit, not to ~/.docker/config.json.
DOCKER_CONFIG="$(mktemp -d)"; export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
aws ecr get-login-password --region "$region" | crane auth login "$registry" -u AWS --password-stdin

# images.yaml: under `images:`, a two-space-indented name, then four-space-indented source, tag, digest, repository.
awk 'function v(x) { gsub(/"/, "", x); return x == "" ? "-" : x }
     /^  [a-z]/ { if (n) print n, s, t, d, r; n = $1; sub(/:$/, "", n); s = t = d = r = "-" }
     /^    source:/ { s = v($2) } /^    tag:/ { t = v($2) } /^    digest:/ { d = v($2) } /^    repository:/ { r = v($2) }
     END { if (n) print n, s, t, d, r }' "$images" > "$DOCKER_CONFIG/images"
# Read on descriptor 3, so no command in the loop can consume the list from standard input.
while read -r name source tag digest repository <&3; do
  ref="$source@$digest"
  if [ "$name" = philter-batch ] && [ -n "${PHILTER_BATCH_IMAGE:-}" ]; then
    ref="$PHILTER_BATCH_IMAGE"; digest="${ref##*@}"
  fi
  if [ "$digest" = - ]; then
    echo "Skipping $name: images.yaml has no digest for it (set PHILTER_BATCH_IMAGE for philter-batch)."
    continue
  fi
  aws ecr describe-repositories --region "$region" --repository-names "$repository" >/dev/null 2>&1 ||
    aws ecr create-repository --region "$region" --repository-name "$repository" --image-tag-mutability IMMUTABLE \
      --image-scanning-configuration scanOnPush=true --encryption-configuration encryptionType=AES256 >/dev/null
  if [ "$(crane digest "$registry/$repository:$tag" 2>/dev/null)" = "$digest" ]; then
    echo "$name is already in $registry at $digest."
  else
    crane copy "$ref" "$registry/$repository:$tag"
  fi
done 3< "$DOCKER_CONFIG/images"
