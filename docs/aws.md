# AWS

`deploy/terraform/aws` deploys a temporary Philter Batch environment into one AWS account: a VPC with no route to the
internet by default (see [Internet access](#internet-access)), or an existing VPC, an EKS cluster with a private endpoint, an on-demand node group and a Spot worker node
group, the VPC endpoints the nodes need, the workers' and the console's IAM roles bound through EKS Pod Identity, and,
with Helm, Argo Workflows and the Philter Batch chart. An operator applies it, runs the redaction through the console
([Kubernetes](kubernetes.md#run-a-job-from-the-console)), and destroys it. The documents, the output, and every run's
state stay in S3 and outlive the stack.

`terraform apply` does not start a run. Phileas runs in the worker pods; no Philter server is needed.

The Terraform has been validated, linted, and tested against mocked providers only. It has not been applied in an AWS
account.

## Run a job on EKS, step by step

The whole path, from an empty account to a destroyed stack, with the default access host. Each step links to its
details below. Run the commands from the repository's root unless a step changes directory.

1. **Tools and permissions.** Terraform 1.16, the AWS CLI v2, `kubectl`, `crane` (not needed with
   `internet_access`), and the Session Manager plugin,
   with AWS credentials for the customer's account that have the [operator's permissions](#the-operators-permissions)
   and no read on the input bucket.

2. **State bucket, once per account** ([State](#state)):

   ```sh
   (cd deploy/terraform/aws/state-bucket && terraform init && \
     terraform apply -var region=us-east-1 -var bucket=example-philter-batch-tfstate && \
     terraform output -raw backend_config > ../backend.hcl)
   ```

3. **Images** ([Images](#images)). Skip this step with `internet_access = true`: the nodes pull from the public
   registries. Otherwise, copy the images into the account's ECR; until the philter-batch image is published, give
   your build's digest:

   ```sh
   PHILTER_BATCH_IMAGE=registry.example.com/philter-batch@sha256:<digest> scripts/push-images.sh us-east-1
   ```

4. **Variables** ([Variables](#variables)). In `deploy/terraform/aws`, copy `terraform.tfvars.example` to
   `terraform.tfvars` and set the region, the input and output buckets and prefixes,
   `image_digests["philter-batch"]` (the digest from step 3, or with `internet_access` the published one, until
   `images.yaml` pins it), and the worker count.

5. **Apply, twice** ([Apply](#apply)). The first apply creates the network, cluster, and access host; the second
   installs Argo and Philter Batch through the port forward:

   ```sh
   cd deploy/terraform/aws
   terraform init -backend-config=backend.hcl
   terraform apply -var install_software=false
   terraform output -raw access_host_tunnel     # run its command in its own terminal or tmux session; leave it running
   # set kubernetes_api_server = "https://127.0.0.1:8443" in terraform.tfvars, then:
   terraform apply
   terraform output -json kubeconfig_commands   # run them: kubectl now reaches the cluster through the tunnel
   ```

6. **Run the job** ([Kubernetes](kubernetes.md#run-a-job-from-the-console)). Write `run.yaml` with `run_id`, `input`,
   and `output.mode` (the deployment sets the store and output location), and a Phileas policy. Then, with the
   console's `kubectl exec` lines from `terraform output -json console_commands`:

   ```sh
   C="kubectl exec -n philter-batch philter-batch-console-0 --"
   CI="kubectl exec -i -n philter-batch philter-batch-console-0 --"
   $CI philter-batch configure --stdin < run.yaml
   $CI philter-batch set-policy pilot-2026-11 --stdin < policy.json
   $C philter-batch submit pilot-2026-11
   $C philter-batch status pilot-2026-11                                  # until it waits on the sample
   $C philter-batch sample-report pilot-2026-11 > sample-report.html      # the customer reviews it
   $C philter-batch approve pilot-2026-11 sample --by "<reviewer>"        # or reject
   $C philter-batch status pilot-2026-11                                  # until it waits on the release
   $C philter-batch approve pilot-2026-11 release --by "<reviewer>"
   ```

7. **Collect the output.** Under `s3://<output_bucket>/<output_prefix>runs/<run_id>/`: `parts/` (JSONL),
   `documents/` (redacted files, in document mode), `report.json`, and `_SUCCESS`. The manifest that maps output
   IDs to input keys is in `manifest/`, which holds input keys: do not give it to the training pipeline
([formats](formats.md)).

8. **Destroy** ([Destroy](#destroy)), with the tunnel still running:

   ```sh
   terraform output -raw destroy_check          # run it: fails while a workflow is running or waiting
   terraform destroy
   ```

   The buckets, the run's state and output, the state bucket, and the ECR repositories (if any) remain. At the end of the
   engagement, delete the ECR repositories (`aws ecr delete-repository --force`) and, if no longer needed, the state
   bucket.

## Internet access

`internet_access` chooses where the nodes pull images from, and whether they can reach the internet at all.

| | `internet_access = false` (default) | `internet_access = true` |
| --- | --- | --- |
| Images | The account's ECR, copied by `scripts/push-images.sh` | Their public registries, from `images.yaml`'s `source`: `docker.io/philterd/philter-batch` and `quay.io/argoproj/*` |
| Pinning | By digest | By digest, the same digests |
| Route to the internet | None | Outbound only, through one NAT gateway in a public subnet. No inbound route to the nodes. |
| VPC endpoints | `ec2`, `ecr.api`, `ecr.dkr`, `eks`, `eks-auth`, S3 (with ECR's layer bucket) | `ec2`, `eks`, `eks-auth`, S3 (without ECR's layer bucket). AWS traffic still uses them. |
| Extra cost | ECR storage while the repositories exist | The NAT gateway, by the hour and by the GB; each node pulls the philter-batch image (about 1.1 GB) through it |

With `internet_access`, the nodes, anything on the host network, and Argo's controller (in the `argo` namespace,
which has no NetworkPolicy) can reach the internet. The pods in the Philter Batch namespace, which are the ones that
read documents, cannot: the chart's NetworkPolicy, enforced by the VPC CNI, allows them only DNS, the Kubernetes API,
S3, and the Pod Identity agent, the same as without it. Choose the default when the customer requires that the environment have no
route to the internet.

Docker Hub allows 100 anonymous pulls per 6 hours per IPv4 address
([docs.docker.com/docker-hub/usage](https://docs.docker.com/docker-hub/usage/), read 2026-11-27), and every node pulls
through the NAT gateway's one address. Each node pulls the philter-batch image once (`IfNotPresent`), and a Spot node
that is replaced pulls it again. If pulls fail with "You have reached your pull rate limit", use the default (ECR)
instead.

With `create_vpc = false`, the stack creates no NAT gateway: with `internet_access`, the given subnets must already
route to the internet.

Docker Hub and quay.io serve image layers from CloudFront (checked 2026-11-27), so their pulls go through the NAT
gateway rather than the S3 gateway endpoint, whose policy allows only the input and output.

## What it creates, and what it does not

| Created | Notes |
| --- | --- |
| VPC, one private subnet per availability zone (2 by default), one route table | By default no internet gateway and no NAT gateway: the route table holds only the local and S3 gateway routes, and Terraform removes any other route added later. Skipped with `create_vpc = false`. |
| Internet gateway, public subnet (the VPC's last /20), Elastic IP, NAT gateway | Only with `internet_access` and `create_vpc`. The private route table then holds one more route, `0.0.0.0/0` to the NAT gateway. |
| S3 gateway endpoint | Its policy allows only the input (read), the output prefix (read and write), and, without `internet_access`, ECR's layer bucket `prod-<region>-starport-layer-bucket`. |
| Interface endpoints, private DNS on | `ec2`, `eks`, `eks-auth`, `ecr.api` and `ecr.dkr` (without `internet_access`), and, for the access host, `ssm`, `ssmmessages`, `ec2messages`. |
| EKS cluster | Kubernetes 1.35, private endpoint, `API` authentication mode, standard support only, all control plane log types to a log group the stack owns. |
| Add-ons | VPC CNI with `enableNetworkPolicy`, kube-proxy, EKS Pod Identity agent, CoreDNS, at EKS's default version for the Kubernetes version unless `addon_versions` pins them. |
| Node groups | `system`: on demand, label `philterd.ai/node-pool=system`. `workers`: Spot, label `philterd.ai/node-pool=workers`, taint `philterd.ai/node-pool=workers:NoSchedule`. IMDSv2 with a hop limit of 1, encrypted gp3 volumes. |
| Placement | Set in the chart values the stack passes, not by the charts' defaults. On `system`: Argo's controller and CRD installer, the console, and the workflow's preflight, inventory, gate, and finalize steps. On `workers`, with a toleration for its taint: the sample pod and the worker pods. CoreDNS runs on `system`; the add-ons that run on every node (the VPC CNI, kube-proxy, and the Pod Identity agent) run on both. |
| Access entry | The operator's role or user, as cluster admin. |
| IAM roles | Workers, console, cluster, nodes, access host. |
| Access host | A `t3.micro` with no public address and no inbound rules, for an SSM port forward to the private endpoint. Skipped with `access_host_enabled = false`. |
| Helm releases | Argo Workflows (chart 2.0.18, v4.1.6, no server) in `argo`; Philter Batch in `philter-batch`. |
| Output bucket | Only with `create_output_bucket = true`; kept on destroy. |

Not created: the input bucket (only referenced), the ECR repositories (`scripts/push-images.sh`, without
`internet_access`), the state bucket
(`state-bucket/`), load balancers, ingress, or persistent volumes. Nothing in the cluster creates AWS resources.

The interface endpoints are those AWS lists for private clusters
([private-clusters.html](https://docs.aws.amazon.com/eks/latest/userguide/private-clusters.html), read 2026-11-26)
that this stack uses: `ec2` (the VPC CNI and node names), `ecr.api` and `ecr.dkr` (images), `eks`, and `eks-auth`
(Pod Identity). Not created, because nothing here uses them: `sts` and `oidc-eks` (IRSA), `elasticloadbalancing`,
`logs`, `xray`, `acm`, `route53`, and `autoscaling`. The SSM endpoints are from
[setup-create-vpc.html](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html).

## Prerequisites

On the operator's machine: Terraform 1.16, the AWS CLI v2, `kubectl`, and, with the access host (the default), the
[Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html).
`scripts/push-images.sh` also needs [crane](https://github.com/google/go-containerregistry).

### The operator's permissions

The operator's role is outside this module. It needs to create and delete everything in the table above, and nothing
on the documents. At minimum:

- EKS: clusters, node groups, add-ons (`eks:DescribeAddonVersions`), access entries and access policy associations,
  Pod Identity associations.
- EC2: VPCs, subnets, route tables, security groups and rules, VPC endpoints, launch templates, instances, and
  with `internet_access` internet gateways, NAT gateways, and Elastic IPs,
  `ec2:DescribeNetworkInterfaces` and `ec2:DeleteNetworkInterface` (the destroy-time cleanup), and the `Describe*`
  calls Terraform makes.
- IAM: create, tag, and delete the five roles, their inline policies, attachments, and the access host's instance
  profile; `iam:PassRole` on the cluster, node, and access host roles; `iam:GetRole` on its own role
  (`aws_iam_session_context`).
- CloudWatch Logs: create, tag, and delete `/aws/eks/<name>/cluster`.
- SSM: `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`; `ssm:StartSession` on the access host with
  the `AWS-StartPortForwardingSessionToRemoteHost` document, and `ssm:TerminateSession` on its own sessions.
- ECR (not with `internet_access`): for `push-images.sh`, `ecr:GetAuthorizationToken`, and on the Philter Batch repositories
  `ecr:DescribeRepositories`, `ecr:CreateRepository`, `ecr:BatchGetImage`, `ecr:BatchCheckLayerAvailability`,
  `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, and `ecr:PutImage`; for the plan,
  `ecr:DescribeImages` on them.
- S3: on the state bucket only, `s3:ListBucket`, and `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on the state
  key and its `.tflock` object. With `create_output_bucket`, creating and configuring that bucket.
- KMS: with `secrets_kms_key_arn`, `kms:DescribeKey` and `kms:CreateGrant` on it.
- STS: `sts:GetCallerIdentity`.

No read on the input bucket: Terraform only names it. Add an explicit deny to the operator's role so nothing else
grants it:

```json
{"Effect": "Deny", "Action": ["s3:GetObject", "s3:GetObjectVersion"], "Resource": "arn:aws:s3:::<input bucket>/*"}
```

The operator is cluster admin, and so can still start a pod as the workers' service account and act with the workers'
role. The IAM boundary keeps the documents out of the operator's own AWS credentials; it does not stop a cluster admin.
The console cannot do it on the operator's behalf: Argo runs only workflows that reference a `WorkflowTemplate`
(`templateReferencing: Strict`).

The console's `sample-report` prints the redacted sample report to the operator's terminal. It holds redacted text
only.

## State

State goes to an S3 bucket in the customer's account, created once per account with `state-bucket/` (versioned,
encrypted, public access blocked, TLS only, `prevent_destroy`). It is not part of the stack.

```sh
cd deploy/terraform/aws/state-bucket
terraform init
terraform apply -var region=us-east-1 -var bucket=example-philter-batch-tfstate
terraform output -raw backend_config > ../backend.hcl
```

The bootstrap's own state is local; keep it with the engagement, or discard it and delete the bucket by hand at the end
of the engagement (`aws s3 rb s3://<bucket> --force` after removing its versions). `backend.hcl` and `*.tfvars` are
ignored by git: an engagement's values stay with the engagement.

## Images

With `internet_access`, the nodes pull each image from its `source` in `images.yaml`, by digest, and nothing needs
copying. Without it (the default), the nodes cannot reach the internet, so every image comes from the account's ECR,
copied by digest beforehand:

```sh
scripts/push-images.sh us-east-1
```

It copies into the ECR of the account your AWS CLI credentials belong to; see
[Kubernetes](kubernetes.md#images) for what it does and for copying a philter-batch build before the image is
published.

The stack reads `deploy/helm/philter-batch/images.yaml` for the sources, repositories, tags, and digests, and without
`internet_access` looks each image up in ECR at plan time, so a missing image fails the plan. While `images.yaml` does not pin a
`philter-batch` digest, give it in `image_digests`; a digest given for an image that `images.yaml` pins must equal it.
`images.yaml` includes Argo's CRD installer (`argo-workflows-crdinstaller`), which Argo's chart runs as a Job before
installing.

## Variables

Copy `terraform.tfvars.example` to `terraform.tfvars`. Required: `region`, `input_bucket`, `output_bucket`, and, until
the philter-batch image is published, `image_digests["philter-batch"]`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `input_bucket`, `input_prefix` | required, `""` | The documents. Only referenced. |
| `input_kms_key_arns` | `[]` | SSE-KMS keys of the input; the workers may decrypt, through S3 only. |
| `output_bucket`, `output_prefix` | required, `philter-batch/` | Where runs write. Must not overlap the input. |
| `output_kms_key_arns` | `[]` | SSE-KMS keys of the output. |
| `create_output_bucket` | `false` | Create the output bucket (versioned, encrypted, kept on destroy). |
| `internet_access` | `false` | Outbound internet through a NAT gateway, and images from their public registries. See [Internet access](#internet-access). |
| `create_vpc` | `true` | Otherwise `vpc_id`, `subnet_ids` (two zones or more), `route_table_ids`. |
| `vpc_cidr`, `availability_zone_count` | `10.80.0.0/16`, `2` | |
| `create_vpc_endpoints` | `true` | Turn off only if the VPC already has all of them. |
| `kubernetes_version` | `1.35` | |
| `service_cidr` | `172.20.0.0/16` | Must not overlap the VPC. |
| `cluster_public_access_cidrs` | `[]` | A public endpoint limited to these CIDRs. Empty: private only. |
| `access_host_enabled` | `true` | The SSM access host. |
| `kubernetes_api_server` | null | The address Terraform uses for the API, such as `https://127.0.0.1:8443` through the access host. |
| `operator_principal_arn` | the caller's role or user | Given cluster admin. |
| `system_nodes`, `worker_nodes` | see `variables.tf` | Instance types and sizes. Workers default to 4 Spot nodes of 8 vCPUs, one worker pod each. |
| `workers` | 4 pods, 4 CPUs, `6Gi`, 1 thread | The chart's worker pool. Keep `worker_nodes.desired_size` at or above `workers.count`; there is no autoscaler. |
| `extra_chart_values` | `[]` | Further Philter Batch chart values, as YAML. |
| `install_software` | `true` | `false` for the first apply through the access host. |
| `check_images_in_ecr` | `true` | Not used with `internet_access`. |
| `allow_destroy_while_running` | `false` | Skip the destroy check. Takes effect after an apply. |
| `secrets_kms_key_arn` | null | A customer key for Kubernetes secrets; EKS envelope-encrypts API data with an AWS owned key otherwise. |
| `cluster_log_types`, `cluster_log_retention_days` | all five, `90` | The log group is deleted with the stack. |

## Apply

```sh
cd deploy/terraform/aws
terraform init -backend-config=backend.hcl
```

The cluster's endpoint is private. Terraform's Helm provider and `kubectl` reach it in one of three ways.

**Through the access host (the default).** The host does not exist before the first apply, so apply twice:

```sh
terraform apply -var install_software=false
terraform output -raw access_host_tunnel      # run this command in its own terminal or tmux session; leave it running
terraform apply -var kubernetes_api_server=https://127.0.0.1:8443
```

Set `kubernetes_api_server` in `terraform.tfvars` once the host exists, so every later plan and the destroy use the
port forward. Then point `kubectl` at it:

```sh
terraform output -json kubeconfig_commands    # aws eks update-kubeconfig ..., then kubectl config set-cluster ... --server=https://127.0.0.1:8443 --tls-server-name=<endpoint>
```

**From the customer's network** (VPN, Direct Connect, or a peered VPC that resolves the VPC's private DNS):
`access_host_enabled = false`, one `terraform apply`, then `aws eks update-kubeconfig`.

**Through a public endpoint limited to the operator's address:** `cluster_public_access_cidrs = ["192.0.2.10/32"]`,
`access_host_enabled = false`, one `terraform apply`. Prefer the first two: the API is then reachable from nowhere else.

## Run

`terraform output -json console_commands` prints the console steps; [Kubernetes](kubernetes.md#run-a-job-from-the-console)
describes them. The customer reviews the sample report and approves.

## Destroy

```sh
terraform output -raw destroy_check   # run it: exits 1 while a workflow is running or waiting at a gate
terraform destroy
```

- **Running workflows.** Destroy runs the same check first and stops if any workflow in the namespace is Pending or
  Running, which includes a run waiting at a gate. A run stopped deliberately (`kubectl delete workflow`) resumes on a
  new stack with `submit`: its state is in the bucket. To skip the check, apply with
  `allow_destroy_while_running = true`, then destroy. Through the access host, keep the port forward running until
  the destroy finishes.
- **A created output bucket** makes `terraform destroy` fail with "Instance cannot be destroyed". Remove it from the
  state first, which leaves it in place:

  ```sh
  terraform state rm 'module.output_bucket[0]'
  ```

- **Order.** The Helm releases are removed first, while the nodes, endpoints, and access host still exist; then the
  node groups and the cluster; then the network. Before the subnets are deleted, `scripts/cleanup-cni-enis.sh`
  deletes network interfaces the VPC CNI left behind (status `available`, tagged
  `node.k8s.amazonaws.com/instance_id`). The cluster's own security group is deleted by EKS with the cluster.
- **Kept:** the input and output buckets, the state bucket, and the ECR repositories, if any (delete those with
  `aws ecr delete-repository --force` at the end of the engagement). The NAT gateway and its Elastic IP are
  destroyed with the stack.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `apply` fails at the Helm releases with a connection error | The API is not reachable: start the port forward and set `kubernetes_api_server`, or use one of the other access paths. |
| The plan fails in `data.aws_ecr_image` | An image is not in ECR at its digest: run `scripts/push-images.sh`. |
| Pods stay `ImagePullBackOff` | Without `internet_access`: the S3 endpoint policy or the `ecr.*` endpoints, or an image was copied to another region or registry than `ecr_registry`. With it: no route to the internet (an existing VPC's subnets), or Docker Hub's pull limit ("You have reached your pull rate limit" in the pod's events). |
| The console, Argo's controller, or a preflight, inventory, gate, or finalize pod stays `Pending` | The `system` group lacks room: they all run there, beside CoreDNS. Raise `system_nodes.desired_size` or use larger `system_nodes.instance_types`. |
| Worker pods stay `Pending` | Fewer worker nodes than `workers.count`, or Spot capacity unavailable for the instance types: add types to `worker_nodes.instance_types`, or set `capacity_type = "ON_DEMAND"`. |
| `check-store` fails with access denied | The workers' role, its Pod Identity association, or the network policy's egress to the Pod Identity agent (`169.254.170.23:80`). |
| `destroy` stops at `workflow_guard` | A workflow is running; see Destroy. With exit 2, the API was not reachable. |
| `destroy` fails deleting a subnet or security group | An ENI is still attached: rerun `terraform destroy`, or run `scripts/cleanup-cni-enis.sh --region <r> --vpc-id <vpc>`. |

On EKS, the chart's NetworkPolicy allows the API at the cluster subnets' CIDRs and at the `kubernetes` Service IP, S3
at the region's S3 prefix list, and the Pod Identity agent. This is untested on EKS; if a pod cannot reach one of
them, check the network policy agent's logs on the node (`aws-eks-nodeagent` container of `aws-node`).
