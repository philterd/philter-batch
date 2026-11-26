# Philter Batch

Philter Batch redacts a large body of documents in object storage, for example to prepare AI training data. It
runs [Phileas](https://github.com/philterd/phileas) and the PhEye model in-process, so it needs no Philter
server, and it keeps all of its coordination state in the same object storage as the data, so it needs no
database or queue. A run can be stopped and resumed on any machine that can reach the bucket.

This first version handles plain text files in Amazon S3 (or an S3-compatible store). Other files are recorded
as skipped and never copied. A run can be driven step by step, in one process, or on Kubernetes with Argo
Workflows, the Helm chart in `deploy/helm/philter-batch`, and its console pod ([Kubernetes](docs/kubernetes.md)).

## How a run works

1. **Inventory** lists the input, chooses the plain-text files by extension, splits large files into line-range
   segments, chooses a sample (50 entries by default, spread across file types and sizes), and writes shards of
   about 128 MiB each.
2. **Sample.** The sample is redacted first, into `sample/`, with a JSON report and a self-contained HTML report:
   the redacted text with the replacements highlighted by type, counts, quarantines, the measured throughput, and
   the estimated time and cost of the rest. The run waits until a person approves the sample, or ends if they
   reject it.
3. **Workers** claim shards through conditional writes to the store, redact each file with Phileas, and write
   redacted text as JSONL, a redacted file of the same type, or both. A file that cannot be decoded or
   redacted is quarantined with a reason code, and nothing is written for it. The sample is not redone.
4. **Finalize** removes output from workers that lost their shards, assembles redacted documents, and writes a
   manifest, a report, and `_SUCCESS`. On Kubernetes the run then waits at a release gate.

Output never carries input keys or file names: records use an opaque ID, and the mapping back to keys is only in
the run's manifest. Logs carry IDs, counts, and reason codes, never text or detected values.

Redaction is probabilistic and does not catch every value; validate output against your own data before use.
Phileas's leftover check is not run for now; see [configuration](docs/configuration.md).

## Build

Philter Batch builds against Phileas 4.6.0-SNAPSHOT, from Maven Central's snapshot repository, until Phileas
4.6.0 is released. `./mvnw -Prelease verify` refuses snapshot dependencies.

Java 25 and Maven (the wrapper is included):

```sh
./mvnw verify
```

This runs the unit tests and the integration tests, which start [RustFS](https://github.com/rustfs/rustfs) in
Docker as an S3-compatible store. The test that runs the PhEye model is skipped unless
`PHILTER_BATCH_TEST_MODELS` names a directory holding `ph-eye-pii-en-small`.

The image bundles the model at a pinned revision:

```sh
docker build -t philter-batch:dev .
```

## Run

Write a configuration ([reference](docs/configuration.md), [example](examples/run.yaml)) and a Phileas policy
([example](examples/policy.json)), then check that the store supports the coordination:

```sh
java -jar target/philter-batch.jar check-store -c run.yaml
```

Run everything in one process, with as many worker threads as configured (the pilot mode). It redacts the sample,
then waits at the sample gate; review `sample/report.html` in the run's prefix and approve or reject from another
shell:

```sh
java -jar target/philter-batch.jar run -c run.yaml
java -jar target/philter-batch.jar approve -c run.yaml sample --by "Avery Example"   # or reject
```

Or step by step, with any number of worker processes on any number of machines:

```sh
java -jar target/philter-batch.jar inventory -c run.yaml
java -jar target/philter-batch.jar sample -c run.yaml
java -jar target/philter-batch.jar approve -c run.yaml sample
java -jar target/philter-batch.jar work -c run.yaml      # on each worker
java -jar target/philter-batch.jar finalize -c run.yaml
```

`work` refuses to start until the sample is approved. Set `sample.enabled: false` to skip the sample.

With the image, mount the configuration and policy and pass the same arguments:

```sh
docker run --rm --read-only --tmpfs /tmp:exec -v "$PWD:/config:ro" philter-batch:dev run -c /config/run.yaml
```

The image keeps nothing on disk and runs with a read-only root file system, but needs a writable `/tmp`, where
ONNX Runtime and the tokenizer extract and load their native libraries at startup, so it must allow execution
(Docker mounts `--tmpfs` with `noexec` unless given `:exec`). In Kubernetes, mount an `emptyDir`
with `medium: Memory` there.

On Kubernetes, install Argo Workflows and the Helm chart, then work through the console pod, one `kubectl exec`
line per step: `configure`, `set-policy`, `submit`, `status`, `sample-report`, `approve` or `reject`. See
[Kubernetes](docs/kubernetes.md). `scripts/push-images.sh` copies the images pinned in
`deploy/helm/philter-batch/images.yaml`, by digest, into the account's private ECR.

On AWS, the Terraform in `deploy/terraform/aws` creates a temporary environment for a run (a private VPC, an EKS
cluster with an on-demand and a Spot node group, the VPC endpoints, the IAM roles, Argo Workflows, and the chart) and
destroys it afterwards without touching the data. See [AWS](docs/aws.md).

`work` exits 0 when no work is left, 4 when work remains but every shard is claimed by another worker, and 3
when stopped by SIGTERM after saving progress. The [formats](docs/formats.md) page lists every exit code.

## Output

Under `<output.prefix>runs/<run_id>/`:

- `parts/*.jsonl`: redacted text, one record per file or segment ([record schema](docs/record-schema.md)).
- `documents/`: redacted files, named by opaque ID (document and both modes).
- `report.json` and `_SUCCESS`: counts by status, reason, and PII type; the policy hash; the Phileas versions and
  the SHA-256 of each model; and the image digest when `PHILTER_BATCH_IMAGE_DIGEST` is set.
- `manifest/`: the mapping from opaque IDs to input keys, and every skipped object. It holds file names, which
  can contain names: do not give it to the training pipeline.
- `sample/`: the sample's redacted records or documents, `report.json`, and `report.html`. Redacted text only.
- `gates/`: the decisions at the sample and release gates, with who decided and when.
- `config/`: in a deployment, the run's configuration and policy as stored by the console.

The run's coordination objects (shards, claims, progress, done markers) are specified in
[docs/formats.md](docs/formats.md).
