#!/usr/bin/env bash
# Copies every image a Philter Batch run needs, by digest, from where it is published into a private registry: an
# Amazon ECR registry in the customer's account, or, for testing, a registry on this machine. It never pushes to a
# public registry. The images and digests are pinned in deploy/helm/philter-batch/images.yaml.
#
# Idempotent: an image already in the target at its digest is skipped, and a missing ECR repository is created with
# encryption and scan on push. A new version is another run of this script.
#
# Usage:
#   scripts/push-images.sh --account 111122223333 --region us-east-1 [--kms-key ARN] [--dry-run]
#   scripts/push-images.sh --registry localhost:5000 [--dry-run]
#   scripts/push-images.sh --check
#
# Options:
#   --account ID, --region R  The ECR registry ID.dkr.ecr.R.amazonaws.com. The AWS CLI's credentials must belong to
#                             that account and allow ECR repository creation and push (no access to any bucket).
#   --kms-key ARN             Encrypt new ECR repositories with this KMS key instead of AES-256.
#   --registry HOST[:PORT]    A registry on localhost or 127.0.0.1 instead of ECR, for testing. No AWS calls.
#   --images FILE             The images file (default: deploy/helm/philter-batch/images.yaml).
#   --only NAME               Copy only this image (repeatable).
#   --dry-run                 Print every AWS and crane command instead of running it.
#   --check                   Check the images file's format and exit.
#
# Needs bash, the AWS CLI (for ECR), and crane v0.22.1, which is downloaded and checked against its SHA-256 when it is
# not on the PATH at that version.
set -euo pipefail

CRANE_VERSION=0.22.1
# From go-containerregistry's checksums.txt for v0.22.1.
declare -A CRANE_SHA256=(
  [Linux_x86_64]=0ab7a1d6932a213aed964ce97666c3077fe691c8606413674a8b3e0b9ec4cda0
  [Linux_arm64]=898c0cff975f898a33e8c4580bdafb0e7c02c7faa33374e946762f97c4ab7110
  [Darwin_x86_64]=6fedd06a648c11335f0e8b9547e4783002c30e9336047fec292942cd518bb799
  [Darwin_arm64]=2231fc8df8806d20d680ff1225db44e095a55dd6ac1ae8eced4faf4b278b78fb
)

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
images_file="$here/../deploy/helm/philter-batch/images.yaml"
account="" region="" kms_key="" registry="" dry_run=false check=false
only=()

die() { printf 'push-images: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --account) account="${2:?}"; shift 2 ;;
    --region) region="${2:?}"; shift 2 ;;
    --kms-key) kms_key="${2:?}"; shift 2 ;;
    --registry) registry="${2:?}"; shift 2 ;;
    --images) images_file="${2:?}"; shift 2 ;;
    --only) only+=("${2:?}"); shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    --check) check=true; shift ;;
    -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

# --- The images file -------------------------------------------------------------------------------------------------

[ -f "$images_file" ] || die "no images file at $images_file"
names=() ; declare -A source tag digest repository
while IFS=$'\t' read -r name field value; do
  if [ -z "$field" ]; then
    names+=("$name")
  else
    case "$field" in
      source) source[$name]="$value" ;;
      tag) tag[$name]="$value" ;;
      digest) digest[$name]="$value" ;;
      repository) repository[$name]="$value" ;;
      *) die "$images_file: unknown field '$field' under $name" ;;
    esac
  fi
done < <(awk '
  /^[[:space:]]*(#|$)/ { next }
  /^images:[[:space:]]*$/ { inside = 1; next }
  /^[^ ]/ { inside = 0; next }
  inside && /^  [A-Za-z0-9._-]+:[[:space:]]*$/ { name = $1; sub(/:$/, "", name); print name "\t\t"; next }
  inside && /^    [a-z]+:/ {
    line = $0; sub(/^    /, "", line); field = line; sub(/:.*/, "", field); value = line; sub(/^[a-z]+:[[:space:]]*/, "", value)
    sub(/[[:space:]]+#.*$/, "", value); gsub(/^"|"$/, "", value); print name "\t" field "\t" value; next
  }
  inside { print "malformed\t\t"; exit }
' "$images_file")

[ "${#names[@]}" -gt 0 ] || die "$images_file lists no images"
for n in "${names[@]}"; do
  [ "$n" != malformed ] || die "$images_file: a line under images: is not in the expected format"
  [ -n "${source[$n]:-}" ] || die "$images_file: $n has no source"
  [ -n "${tag[$n]:-}" ] || die "$images_file: $n has no tag"
  [ -n "${repository[$n]:-}" ] || die "$images_file: $n has no repository"
  [[ "${source[$n]}" =~ ^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+$ ]] || die "$images_file: $n's source is not a repository without a tag"
  [[ "${repository[$n]}" =~ ^[a-z0-9][a-z0-9._/-]*$ ]] || die "$images_file: $n's repository is not valid"
  [[ -z "${digest[$n]:-}" || "${digest[$n]}" =~ ^sha256:[a-f0-9]{64}$ ]] || die "$images_file: $n's digest is not sha256:<64 hex>"
done

if $check; then
  for n in "${names[@]}"; do
    if [ -n "${digest[$n]:-}" ]; then
      printf '%-22s %s:%s@%s\n' "$n" "${source[$n]}" "${tag[$n]}" "${digest[$n]}"
    else
      printf '%-22s %s:%s (no digest: not published yet)\n' "$n" "${source[$n]}" "${tag[$n]}"
    fi
  done
  exit 0
fi

selected=()
if [ "${#only[@]}" -gt 0 ]; then
  for o in "${only[@]}"; do
    [ -n "${source[$o]:-}" ] || die "--only $o: no such image in $images_file"
    selected+=("$o")
  done
else
  selected=("${names[@]}")
fi
for n in "${selected[@]}"; do
  [ -n "${digest[$n]:-}" ] || die "$n has no digest in $images_file: it is not published yet. Pin its digest, or leave it out with --only."
done

# --- The target registry: a private ECR registry, or a local one for testing; never a public registry ----------------

ecr=false
if [ -n "$registry" ]; then
  [ -z "$account$region$kms_key" ] || die "--registry cannot be combined with --account, --region, or --kms-key"
  [[ "$registry" =~ ^(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]] \
    || die "--registry must be a registry on localhost or 127.0.0.1 (for testing); for ECR use --account and --region"
else
  [[ "$account" =~ ^[0-9]{12}$ ]] || die "--account must be a 12-digit AWS account ID"
  [[ "$region" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die "--region must be an AWS region such as us-east-1"
  registry="$account.dkr.ecr.$region.amazonaws.com"
  ecr=true
fi
# A second guard on what is about to be pushed to, whatever the arguments.
[[ "$registry" =~ ^[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com$ || "$registry" =~ ^(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]] \
  || die "refusing to push to $registry: only a private ECR registry or a local registry"

# --- Running commands ------------------------------------------------------------------------------------------------

run() {
  if $dry_run; then
    printf '+'; printf ' %q' "$@"; printf '\n'
  else
    "$@"
  fi
}

crane=""
find_crane() {
  if command -v crane >/dev/null 2>&1 && [ "$(crane version 2>/dev/null)" = "$CRANE_VERSION" ]; then
    crane="$(command -v crane)"; return
  fi
  local os arch platform cache
  os="$(uname -s)"; arch="$(uname -m)"
  case "$arch" in aarch64) arch=arm64 ;; esac
  platform="${os}_${arch}"
  [ -n "${CRANE_SHA256[$platform]:-}" ] || die "no pinned crane for $platform; install crane $CRANE_VERSION on the PATH"
  cache="${XDG_CACHE_HOME:-$HOME/.cache}/philter-batch/crane-$CRANE_VERSION-$platform"
  if [ ! -x "$cache/crane" ]; then
    local tmp; tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/crane.tar.gz" \
      "https://github.com/google/go-containerregistry/releases/download/v$CRANE_VERSION/go-containerregistry_$platform.tar.gz"
    printf '%s  %s\n' "${CRANE_SHA256[$platform]}" "$tmp/crane.tar.gz" | sha256sum -c - >/dev/null \
      || die "the downloaded crane does not match its pinned SHA-256"
    mkdir -p "$cache"
    tar -xzf "$tmp/crane.tar.gz" -C "$cache" crane
    rm -rf "$tmp"
  fi
  crane="$cache/crane"
}

if $dry_run; then
  crane=crane
else
  find_crane
  # Registry credentials (the ECR login) go to a temporary configuration, deleted on exit.
  DOCKER_CONFIG="$(mktemp -d)"; export DOCKER_CONFIG
  trap 'rm -rf "$DOCKER_CONFIG"' EXIT
fi

# --- ECR: the account check, the repositories, and the login ---------------------------------------------------------

if $ecr; then
  if $dry_run; then
    run aws sts get-caller-identity --query Account --output text
  else
    caller="$(aws sts get-caller-identity --query Account --output text)"
    [ "$caller" = "$account" ] || die "the AWS CLI's credentials are for account $caller, not $account"
  fi
  if [ -n "$kms_key" ]; then encryption="encryptionType=KMS,kmsKey=$kms_key"; else encryption="encryptionType=AES256"; fi
  for n in "${selected[@]}"; do
    repo="${repository[$n]}"
    if ! $dry_run && aws ecr describe-repositories --region "$region" --repository-names "$repo" >/dev/null 2>&1; then
      printf 'Repository %s exists.\n' "$repo"
    else
      $dry_run && run aws ecr describe-repositories --region "$region" --repository-names "$repo"
      run aws ecr create-repository --region "$region" --repository-name "$repo" \
        --image-tag-mutability IMMUTABLE --image-scanning-configuration scanOnPush=true \
        --encryption-configuration "$encryption" --output text --query repository.repositoryUri
    fi
  done
  if $dry_run; then
    printf '+ aws ecr get-login-password --region %q | crane auth login %q -u AWS --password-stdin\n' "$region" "$registry"
  else
    aws ecr get-login-password --region "$region" | "$crane" auth login "$registry" -u AWS --password-stdin
  fi
fi

# --- The copies --------------------------------------------------------------------------------------------------------

for n in "${selected[@]}"; do
  src="${source[$n]}@${digest[$n]}"
  dst="$registry/${repository[$n]}:${tag[$n]}"
  if ! $dry_run; then
    current="$("$crane" digest "$dst" 2>/dev/null || true)"
    if [ "$current" = "${digest[$n]}" ]; then
      printf '%s is in place at %s.\n' "$dst" "${digest[$n]}"
      continue
    fi
    [ -z "$current" ] || die "$dst exists with digest $current, not ${digest[$n]}; tags are not overwritten"
  fi
  run "$crane" copy "$src" "$dst"
  if ! $dry_run; then
    copied="$("$crane" digest "$registry/${repository[$n]}@${digest[$n]}")"
    [ "$copied" = "${digest[$n]}" ] || die "$dst has digest $copied after the copy, not ${digest[$n]}"
    printf 'Copied %s to %s.\n' "$src" "$dst"
  fi
done

if $dry_run; then
  printf 'Dry run: nothing was changed.\n'
else
  printf 'The images are in %s:\n' "$registry"
  for n in "${selected[@]}"; do printf '  %s/%s@%s\n' "$registry" "${repository[$n]}" "${digest[$n]}"; done
fi
