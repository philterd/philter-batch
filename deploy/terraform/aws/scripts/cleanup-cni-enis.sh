#!/usr/bin/env bash
# Deletes network interfaces that the Amazon VPC CNI created in a VPC and left behind (status available, tagged with
# node.k8s.amazonaws.com/instance_id), which would otherwise keep the subnets from being deleted. Terraform runs it
# on destroy, after the cluster and its nodes are gone. Touches no other network interface.
#
# Usage: cleanup-cni-enis.sh --region REGION --vpc-id VPC_ID [--dry-run]
set -euo pipefail

region="" vpc_id="" dry_run=false
die() { printf 'cleanup-cni-enis: %s\n' "$*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --region) region="${2:?}"; shift 2 ;;
    --vpc-id) vpc_id="${2:?}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$vpc_id" =~ ^vpc-[0-9a-f]+$ ]] || die "--vpc-id must be a VPC ID"
[ -n "$region" ] || die "--region is required"

# A terminated node's interfaces can take a minute or two to become available; check a few times.
for attempt in 1 2 3 4 5 6; do
  enis="$(aws ec2 describe-network-interfaces --region "$region" \
    --filters "Name=vpc-id,Values=$vpc_id" "Name=status,Values=available" "Name=tag-key,Values=node.k8s.amazonaws.com/instance_id" \
    --query 'NetworkInterfaces[].NetworkInterfaceId' --output text)"
  for eni in $enis; do
    if $dry_run; then
      printf 'Would delete %s.\n' "$eni"
    else
      printf 'Deleting %s, left by the VPC CNI.\n' "$eni"
      aws ec2 delete-network-interface --region "$region" --network-interface-id "$eni"
    fi
  done
  in_use="$(aws ec2 describe-network-interfaces --region "$region" \
    --filters "Name=vpc-id,Values=$vpc_id" "Name=status,Values=in-use" "Name=tag-key,Values=node.k8s.amazonaws.com/instance_id" \
    --query 'length(NetworkInterfaces)' --output text)"
  if [ "$in_use" = "0" ] || $dry_run; then
    exit 0
  fi
  printf 'Waiting for %s CNI network interfaces still in use (attempt %s of 6).\n' "$in_use" "$attempt"
  sleep 20
done
printf 'cleanup-cni-enis: CNI network interfaces are still in use in %s; deleting the subnets may fail until they are released.\n' "$vpc_id" >&2
