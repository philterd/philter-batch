# Kubernetes

The Helm chart in `deploy/helm/philter-batch` installs Philter Batch into one namespace: a console pod, the
deployment's store and output location (a ConfigMap), service accounts and the RBAC each needs, a default-deny
NetworkPolicy, and an Argo Workflows `WorkflowTemplate`. Runs are not configured through Helm: the operator
configures, starts, follows, and approves each run from the console, and everything a run has (its configuration,
policy, state, and output) is in the bucket, so it outlives the cluster. The worker pods run Phileas in-process; no
Philter server is needed.

Tested on k3s v1.35.10 with Argo Workflows v4.1.6 (chart `argo-workflows` 2.0.18) and RustFS 1.0.6 as the
object store. It has not been run on EKS, AKS, or GKE.

On AWS, `deploy/terraform/aws` does everything on this page up to [Run a job from the console](#run-a-job-from-the-console):
the cluster, Argo Workflows, the chart, the IAM roles with EKS Pod Identity, and the network policy's destinations. See
[AWS](aws.md); the console steps below are the same.

## Images

Every image a run needs is pinned by digest in `deploy/helm/philter-batch/images.yaml`: `philter-batch` (workers
and console), and Argo Workflows' `workflow-controller`, `argoexec`, and `argo-workflows-crdinstaller` (the Job
Argo's chart runs before installing, to apply its CRDs). The chart reads the philter-batch entry
from it. Argo's server (`argocli`) is not used: the console replaces it.

`scripts/push-images.sh` copies the images, by digest, into the private ECR of the account your AWS CLI credentials
belong to. It needs the AWS CLI and [crane](https://github.com/google/go-containerregistry), and permission to
create ECR repositories and push to them:

```sh
scripts/push-images.sh             # AWS_REGION, AWS_DEFAULT_REGION, or the AWS CLI's region
scripts/push-images.sh us-east-1
```

It creates each missing repository (immutable tags, scan on push, AES-256 encryption), logs `crane` in to ECR with a
temporary configuration, and copies each image from where it is published. An image whose tag is already in the
repository at its pinned digest is skipped, so the script can be run again. An existing repository is used as it
is, with its own settings, but only if its tags are immutable: otherwise the script stops before pushing to it. With immutable tags, ECR refuses to move an existing tag to another digest.

The philter-batch image is not published yet, so `images.yaml` has no digest for it and the script skips it. Push
your build to a registry you can reach and give it with `PHILTER_BATCH_IMAGE=<image>@sha256:<digest>`, and set
`image.digest` to that digest.

The copy runs on your machine: `crane` reads each image from its public registry and writes it to ECR's endpoint
under your AWS credentials, so the layers pass through your connection. ECR is private because every request needs
the account's IAM credentials, not because it is unreachable. The cluster's nodes only read from it, with their own
role, through the VPC endpoints.

## Install Argo Workflows

From its own chart, in its own namespace (the chart's NetworkPolicy denies all traffic in Philter Batch's
namespace), with the images from `images.yaml`, from your registry, without the Argo server:

```sh
REGISTRY=111122223333.dkr.ecr.us-east-1.amazonaws.com
helm install argo-workflows argo-workflows --repo https://argoproj.github.io/argo-helm \
  --version 2.0.18 -n argo --create-namespace \
  --set server.enabled=false \
  --set controller.image.registry=$REGISTRY \
  --set controller.image.tag=v4.1.6@sha256:a012a639ead374a4a9306093d6b29dfdc7e6c4c1a85c684582cb961a6cc8bcf3 \
  --set executor.image.registry=$REGISTRY \
  --set executor.image.tag=v4.1.6@sha256:28432d912a716167709429b28e2755f49416eb4754c35ca3013863fb72717a00 \
  --set crds.upgradeJob.image.registry=$REGISTRY \
  --set crds.upgradeJob.image.tag=v4.1.6@sha256:b9be70ef3f4a0709d771e9c098a99fd2d97be1b095c1b160c668a2fde61dc237 \
  --set controller.workflowRestrictions.templateReferencing=Strict
```

The tags and digests are those in `images.yaml`. Without the `crds.upgradeJob.image` values, the chart's CRD Job
pulls from quay.io, which fails on nodes without internet access. `templateReferencing=Strict` makes Argo run only
workflows that reference a `WorkflowTemplate`, as the console's `submit` does, so the console's permission to create
workflows cannot run arbitrary pods as the workers' service account. Place the controller on regular nodes, not spot
or preemptible ones (`controller.nodeSelector` in that chart).

## Install the chart

Write a values file:

```yaml
image:
  registry: 111122223333.dkr.ecr.us-east-1.amazonaws.com
  digest: sha256:<digest>          # until images.yaml pins the philter-batch image
store: {type: s3, region: us-east-1}
output: {bucket: example-output-bucket, prefix: philter-batch/}
serviceAccount:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/philter-batch-workers     # IRSA
console:
  serviceAccount:
    annotations:
      eks.amazonaws.com/role-arn: arn:aws:iam::111122223333:role/philter-batch-console
workers:
  count: 8
  nodeSelector: {karpenter.sh/capacity-type: spot}
networkPolicy:
  kubernetesApi:
    to: [{ipBlock: {cidr: 10.0.0.0/16}}]
  objectStore:
    to: [{ipBlock: {cidr: 192.0.2.0/24}}]
```

```sh
helm install philter-batch deploy/helm/philter-batch -n philter-batch --create-namespace -f values.yaml
```

Two identities, each through its own service account:

- The workflow's pods (`<release full name>`): read on the input, and read, write, and delete under the output
  prefix.
- The console (`<release full name>-console`): `s3:ListBucket` on the output bucket, and read and write under the
  output prefix, for `configure`, `set-policy`, `approve`, and `reject`, and read for `status` and `sample-report`.
  It deletes nothing and needs nothing on the input.
  In Kubernetes it may create and read Argo workflows in the namespace, and nothing else.

Both identities need `s3:ListBucket` on the output bucket without a prefix condition: the store treats a 404 as a
missing object, and S3 returns 404 rather than 403 only to a caller with `s3:ListBucket` on the bucket; it also reads
the store's clock from HeadBucket, which `s3:ListBucket` authorizes.

With EKS Pod Identity, associate each role with its service account instead of annotating it. Without workload
identity, set `credentialsSecret` to a Secret holding `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`; every pod,
the console's included, gets it.

The chart writes the store and output location to `deployment.yaml` in its ConfigMap. Every pod reads it, so a run
is named by its ID alone. One release holds any number of runs, one running at a time (an Argo mutex; a second
submission waits).

## Run a job from the console

The console is `<release full name>-console-0` (`philter-batch-console-0` for a release named `philter-batch`).
Every step is one `kubectl exec` line, run from the operator's machine:

```sh
C="kubectl exec -n philter-batch philter-batch-console-0 --"
CI="kubectl exec -i -n philter-batch philter-batch-console-0 --"
```

1. **Configure the run.** `run.yaml` ([configuration](configuration.md)) names the run, its input, and its
   output mode. Leave out `store`, `output.bucket`, and `output.prefix` (the deployment sets them), and `policy`,
   `worker.threads`, `worker.onnx_threads`, and `worker.shutdown_grace_seconds` (the chart's `workers` values set
   them). Both are checked before anything is stored, and can be replaced until the run starts. `set-policy`
   refuses a policy holding a key, refuses `FPE_ENCRYPT_REPLACE` and `CRYPTO_REPLACE` when the deployment has no
   pseudonymization secret, and prints a warning for `CRYPTO_REPLACE`, unsalted `HASH_SHA256_REPLACE`, and
   `LAST_4` ([strategies and keys](configuration.md#strategies-and-keys)).

   ```sh
   $CI philter-batch configure --stdin < run.yaml
   $CI philter-batch set-policy pilot-2026-11 --stdin < policy.json
   ```

2. **Start it.** `submit` checks the configuration and policy again, then starts the workflow.

   ```sh
   $C philter-batch submit pilot-2026-11
   $C philter-batch status pilot-2026-11
   ```

3. **Review the sample.** After the inventory, a sample is redacted first (50 entries by default), and the run
   waits. `status` shows `waiting for a decision on the sample (waiting on a person)`. Fetch the report and open
   it locally; it needs no server and makes no requests:

   ```sh
   $C philter-batch sample-report pilot-2026-11 > sample-report.html
   ```

   It shows each sampled entry's redacted text with the replacements highlighted by type, the policy's warnings,
   counts by type, quarantines by reason, the measured throughput, and the estimated time of the rest for `workers.count` pods
   (and its cost, when the run sets `sample.price_per_worker_hour`). It holds redacted text only, never an original
   value or an input key. Do not use `kubectl exec -t`: a terminal changes the line endings.

4. **Decide.** The reviewer (the customer, in a consulting engagement) approves or rejects:

   ```sh
   $C philter-batch approve pilot-2026-11 sample --by "Avery Example"
   $C philter-batch reject pilot-2026-11 sample --by "Avery Example" --note "names in headers missed"
   ```

   The decision is a marker object under the run's prefix (`gates/sample-approved.json`), with who, when, any
   note, and the SHA-256 of the report reviewed. Anyone who can write there can decide without the cluster, for
   example `aws s3 cp decision.json s3://<bucket>/<prefix>runs/<run_id>/gates/sample-approved.json`: the object's
   presence is the decision, whatever it holds. The workflow notices within `gates.pollSeconds`.

   On approval, the worker pool redacts the rest; the sampled entries are not redone. On rejection, the workflow
   ends, Succeeded, without running anything past the sample, and the run cannot be resumed: a run is tied to its
   policy, so a changed policy is a new run with a new `run_id`.

5. **Release.** After finalize, the run waits again at the release gate. Review `report.json` and the output, then:

   ```sh
   $C philter-batch approve pilot-2026-11 release --by "Avery Example"
   ```

   When more entries were quarantined than `quarantine.max_percent` (5% by default), `status` says so and the
   approval is refused. Review `quarantined_by_reason` in `report.json`, and the quarantined entries in
   `manifest/`, then reject, or approve with `--accept-quarantine`, which the decision records.

   `status` shows `finished`. Whether the release gate stays, now that the sample gate exists, is an open decision;
   it is on by default and is turned off with `release_gate.enabled: false` in the run's configuration.

## The workflow

| Step | What it does | Retried on |
| --- | --- | --- |
| `preflight` | `check-store`: conditional writes and the clock difference. | pod error, exit 137 or 143 |
| `inventory` | Lists the input, chooses the sample, and writes the shards. Does nothing if the inventory exists. | pod error, exit 137 or 143 |
| `sample` | Redacts the sample and writes `sample/`, in one pod shaped like a worker. Does nothing for a run without a sample or once its report exists. | pod error, exit 3, 137, or 143 |
| `sample-gate` | Waits for `gates/sample-approved.json` or `-rejected.json`. Approved at once for a run without a sample. | pod error, exit 3, 137, or 143 |
| `workers` | Only when the sample is approved: `workers.count` pods (Argo `parallelism`), each running `work` until no shard is left. | pod error, exit 3, 4, 137, or 143, with backoff |
| `finalize` | Cleans up and writes the manifest, report, and `_SUCCESS`. Exits 6 when the quarantines are above `quarantine.max_percent` and `release_gate.enabled` is false. | pod error, exit 137 or 143 |
| `release-gate` | Waits for `gates/release-approved.json` or `-rejected.json`. Approved at once when `release_gate.enabled` is false. | pod error, exit 3, 137, or 143 |
| `sample-rejected`, `release-rejected` | On a rejection, log it. | |
| `exit-handler` | Logs the run's status and failed steps. Touches neither input nor output. | |

Exit 1 (error) and 2 (usage) are never retried, nor are finalize's 5 (a shard has no done marker) and 6 (the
quarantine limit, with no release gate). A worker
exiting 4 (the remaining shards are claimed by other pods) is retried with backoff (30 s, doubling, capped at
5 minutes, up to `workers.retry.limit` times) so that it takes over the claim of a pod that died once the
claim's lease expires. Exit codes are listed in [formats](formats.md#exit-codes).

The gates are enforced by the application too: `work` refuses to start while the sample waits or was rejected.

Not built yet: the canary preflight, the OCR pool, QA scoring (Philter Scope and the second leftover scan), and
the cleanup step.

## Watch and resume

`status` reads the run's objects and its workflows: the stage, whether it waits on a person, shards done, the
entries quarantined so far by reason against `quarantine.max_percent`, and each gate's decision. `--json` prints
the same for scripts. Before finalize, the quarantine counts are those of the completed shards, from `counts/`;
after it, the run's, from `report.json`. Progress is done markers over shards. A worker step that
failed with exit 4 and was retried is expected.

If the workflow fails or is deleted, fix the cause (with `helm upgrade` if it is a value) and submit again:

```sh
$C philter-batch submit pilot-2026-11
```

A new workflow for the same run resumes it: inventory does nothing once complete, the sample step does nothing once
its report exists, a gate already decided passes at once, workers resume from the shards' progress, and finalize can
be repeated. `status` says when no workflow is running a run that has not ended.

## Network policy

The chart denies all ingress and egress in the namespace, then lets the run's pods and the console reach DNS, the
Kubernetes API, the object store, and, with a pseudonymization secret, Secrets Manager. It is enforced only by a CNI that implements NetworkPolicy. Both destinations
are per-cluster and required:

- `networkPolicy.kubernetesApi.to`: the API server's endpoint addresses, which Argo's wait container in each pod
  and the console call. NetworkPolicy matches the endpoint, not the `kubernetes` service IP:
  `kubectl get endpointslices -n default -l kubernetes.io/service-name=kubernetes`. Set
  `networkPolicy.kubernetesApi.ports` to their port.
- `networkPolicy.objectStore.to`: on EKS, route S3 through a gateway endpoint and list the CIDRs of the region's
  S3 prefix list (`aws ec2 describe-managed-prefix-lists`); for another S3-compatible store, its addresses, or a
  `namespaceSelector` and `podSelector` if it runs in the cluster.

With a pseudonymization secret (`pseudonymization.secretArn`), `networkPolicy.secretsManager.to` is required too:
the addresses of the VPC's `secretsmanager` interface endpoint, such as the VPC's CIDR.

With EKS Pod Identity, pods get credentials from the agent on each node at `169.254.170.23`, port 80: add it to
`networkPolicy.extraEgress`. The AWS Terraform sets all of these.

Standard NetworkPolicy matches addresses, not host names. A rule by host name (`s3.us-east-1.amazonaws.com`)
needs a CNI with its own policy type, such as Cilium's `CiliumNetworkPolicy` with `toFQDNs`; disable the chart's
policy (`networkPolicy.enabled: false`) and write that one instead.

## Pods

Every pod runs as user 10001 with a read-only root file system, no capabilities, and the `RuntimeDefault`
seccomp profile. `/tmp` is a memory-backed `emptyDir`, where ONNX Runtime and the tokenizer extract native
libraries; Kubernetes mounts it without `noexec`. It counts against the pod's memory limit.

Worker pods and the sample pod request whole CPUs equal to their limit. The chart passes each its threads
(`workers.threads`), ONNX Runtime's threads (`workers.cpu / workers.threads`), and its shutdown grace
(`workers.shutdownGraceSeconds`), which replace the run configuration's. On SIGTERM a worker finishes or abandons
the file in hand within the grace, saves its progress, and exits 3; `workers.terminationGracePeriodSeconds` must be
longer. On spot nodes, use a node termination handler (or Karpenter's interruption handling) so pods get SIGTERM
before the node goes.

The console runs the same image idle (`sleep infinity`) with the `philter-batch` command on its PATH. It has no
volume and keeps no state.

The image is referenced by digest only. The chart repeats the image's entrypoint as the container command,
because Argo's executor does not read it from the image.

## Values

| Value | Default | Meaning |
| --- | --- | --- |
| `image.registry` | none, required | The registry the images were copied to, such as `111122223333.dkr.ecr.us-east-1.amazonaws.com`, or `docker.io` (with `image.repository: philterd/philter-batch`) to pull the published image directly. |
| `image.repository` | from `images.yaml` | The repository in the registry, without a tag. |
| `image.digest` | from `images.yaml` | `sha256:...`. Recorded in `_SUCCESS`. Required while `images.yaml` has none. |
| `image.pullPolicy` | `IfNotPresent` | |
| `imagePullSecrets` | `[]` | |
| `store.type`, `region`, `endpoint`, `path_style` | `s3`, the SDK's region, AWS, `false` | The store, as in a run's configuration. |
| `output.bucket` | none, required | The bucket runs write to. |
| `output.prefix` | `philter-batch/` | Each run writes under `<prefix>runs/<run_id>/`. |
| `serviceAccount.create`, `name`, `annotations` | `true`, the release's full name, `{}` | The workflow's pods. |
| `credentialsSecret` | none | A Secret with `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, when workload identity is not used. |
| `pseudonymization.secretArn` | none | The Secrets Manager secret that keys `FPE_ENCRYPT_REPLACE` and `CRYPTO_REPLACE`, by full ARN ([configuration](configuration.md#pseudonymization-secret)). The chart holds only the ARN; the workflow's service account needs `secretsmanager:GetSecretValue` on it. |
| `pseudonymization.endpoint` | AWS | A Secrets Manager endpoint instead of AWS's, for testing. |
| `console.enabled` | `true` | |
| `console.serviceAccount.create`, `name`, `annotations` | `true`, `<full name>-console`, `{}` | The console. |
| `console.cpu`, `memory` | `1`, `1Gi` | |
| `console.javaOptions`, `tmpSizeLimit` | `-XX:MaxRAMPercentage=50`, `256Mi` | |
| `console.nodeSelector`, `tolerations`, `affinity` | none | |
| `workers.count` | `4` | Worker pods. Also the pod count of the sample report's estimate. |
| `workers.threads` | `1` | Shards processed at once in a pod. |
| `workers.cpu` | `4` | Whole CPUs per pod, request and limit. A multiple of `workers.threads`. |
| `workers.memory` | `6Gi` | Memory per pod, request and limit. |
| `workers.javaOptions` | `-XX:MaxRAMPercentage=50` | The model's memory is outside the heap. |
| `workers.tmpSizeLimit` | `512Mi` | Size of `/tmp`. |
| `workers.shutdownGraceSeconds` | `25` | After SIGTERM, how long a worker waits for the file in hand. |
| `workers.terminationGracePeriodSeconds` | `60` | Above `workers.shutdownGraceSeconds`. |
| `workers.nodeSelector`, `tolerations`, `affinity` | none | Placement on spot or preemptible nodes. The sample pod uses them too. |
| `workers.retry.limit` | `100` | Retries per worker pod. |
| `workers.retry.backoff` | `30s`, factor `2`, cap `5m` | |
| `gates.pollSeconds` | `30` | How often a gate step checks the bucket. |
| `gates.cpu`, `memory`, `javaOptions` | `250m`, `512Mi`, `-XX:MaxRAMPercentage=50` | The gate steps' pods. They use `steps` placement. |
| `steps.cpu`, `steps.memory` | `1`, `4Gi` | For check-store, inventory, and finalize. Inventory and finalize need about 0.5 KB per input file. |
| `steps.javaOptions`, `steps.tmpSizeLimit` | as for workers | |
| `steps.nodeSelector`, `tolerations`, `affinity` | none | Regular nodes. |
| `steps.retryLimit` | `3` | Also for the sample and gate steps. |
| `workflow.podGC` | `OnWorkflowSuccess` | When Argo deletes the pods, and their logs. |
| `workflow.ttlSecondsAfterCompletion` | `0` (kept) | |
| `workflow.podLabels`, `podAnnotations` | `{}` | |
| `networkPolicy.enabled` | `true` | |
| `networkPolicy.dns` | `kube-dns` in `kube-system` | |
| `networkPolicy.kubernetesApi.to`, `ports` | none, `443/TCP` | Required when enabled. |
| `networkPolicy.objectStore.to`, `ports` | none, `443/TCP` | Required when enabled. |
| `networkPolicy.secretsManager.to`, `ports` | none, `443/TCP` | Required when enabled with `pseudonymization.secretArn`. |
| `networkPolicy.extraEgress` | `[]` | Further egress rules. |
