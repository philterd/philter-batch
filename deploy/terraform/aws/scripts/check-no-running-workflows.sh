#!/usr/bin/env bash
# Fails while any Argo workflow in the Philter Batch namespace has not finished: Pending, Running (which includes a
# run waiting at a gate), or not yet given a phase. Terraform runs it before destroying the stack; the operator can
# run it first. Needs the AWS CLI and kubectl, and credentials with an access entry on the cluster.
#
# Usage:
#   check-no-running-workflows.sh --cluster NAME --region REGION --namespace NS [--server https://127.0.0.1:8443]
#
# --server reaches the API through a port forward (the access host), with the endpoint's name checked for TLS.
#
# Exit codes: 0 no workflow is running, 1 at least one is, 2 usage or the cluster could not be asked.
set -euo pipefail

cluster="" region="" namespace="" server=""
die() { printf 'check-no-running-workflows: %s\n' "$*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --cluster) cluster="${2:?}"; shift 2 ;;
    --region) region="${2:?}"; shift 2 ;;
    --namespace) namespace="${2:?}"; shift 2 ;;
    --server) server="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
{ [ -n "$cluster" ] && [ -n "$region" ] && [ -n "$namespace" ]; } || die "--cluster, --region, and --namespace are required"

# A kubeconfig of its own, so the operator's is not changed.
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
export KUBECONFIG="$tmp/kubeconfig"
aws eks update-kubeconfig --region "$region" --name "$cluster" --kubeconfig "$KUBECONFIG" >/dev/null \
  || die "could not write a kubeconfig for cluster $cluster in $region"
if [ -n "$server" ]; then
  context="$(kubectl config current-context)"
  host="$(kubectl config view -o jsonpath="{.clusters[?(@.name==\"$context\")].cluster.server}")"
  kubectl config set-cluster "$context" --server="$server" --tls-server-name="${host#https://}" >/dev/null
fi

if ! out="$(kubectl get workflows.argoproj.io -n "$namespace" \
    -o 'custom-columns=NAME:.metadata.name,PHASE:.status.phase' --no-headers 2>"$tmp/err")"; then
  if grep -q "the server doesn't have a resource type" "$tmp/err"; then
    printf 'No Argo Workflows resources in the cluster: nothing is running.\n'
    exit 0
  fi
  cat "$tmp/err" >&2
  die "could not list workflows in $namespace (is the API reachable? through the access host, start the port forward and pass --server)"
fi

running="$(printf '%s\n' "$out" | awk 'NF && ($2 == "Pending" || $2 == "Running" || $2 == "<none>") { print $1 " (" $2 ")" }')"
if [ -n "$running" ]; then
  printf 'Workflows still running in %s:\n%s\n' "$namespace" "$running" >&2
  printf 'Wait for them, or stop one deliberately with kubectl delete workflow -n %s <name>; its run resumes on a new stack with submit.\n' "$namespace" >&2
  exit 1
fi
printf 'No workflow is running in %s.\n' "$namespace"
