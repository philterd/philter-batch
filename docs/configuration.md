# Configuration

A run is configured in one YAML file. Run locally, it is passed to every command with `-c`. In a deployment (the
Helm chart), the console's `configure` stores it in the bucket and every pod reads it there by run ID; see
[In a deployment](#in-a-deployment). Unknown settings are refused, so a typo fails at startup rather than being
ignored. `examples/run.yaml` lists every setting with its default.

Workers check their configuration against the settings the run was inventoried with (`run.json`, see
[formats](formats.md)) and refuse to start if the output mode, charset, handled types, or extensions differ. The policy is read from the run, not from each worker's file.

## Required

| Setting | Meaning |
| --- | --- |
| `run_id` | The run's name: letters, digits, `.`, `_`, and `-`, up to 64 characters. Everything the run writes is under `<output.prefix>runs/<run_id>/`. A new run needs a new ID. |
| `input.bucket`, `input.prefix` | Where the documents are. Every object under the prefix is listed. The prefix may be empty. |
| `output.bucket`, `output.prefix` | Where the run writes. The output must not overlap the input in the same bucket. |
| `policy` | The Phileas policy file (JSON). Needed by `inventory` and `run`, which store it with the run. Not in a deployment, where `set-policy` stores it. |

## Store

| Setting | Default | Meaning |
| --- | --- | --- |
| `store.type` | `s3` | The object store. S3 is the only one in this version. |
| `store.region` | the SDK's default | The AWS region. |
| `store.endpoint` | AWS | An S3-compatible endpoint instead of AWS. The store must enforce `If-None-Match: *` and `If-Match` on PutObject; check it with `check-store`. |
| `store.path_style` | `false` | Path-style addressing, which S3-compatible stores usually need. |

Credentials come from the AWS SDK's default chain: EKS Pod Identity, IRSA, an instance profile, or the
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables. The identity needs read access to the
input and read, write, and delete access under the output prefix.

## Output

| Setting | Default | Meaning |
| --- | --- | --- |
| `output.mode` | `text` | `text`: redacted text as JSONL ([record schema](record-schema.md)). `document`: a redacted file of the same type for each input, under `documents/`. `both`: both, from one redaction; plain text only, so it is refused when `input_types.handled` names another type. |

## Policy and models

| Setting | Default | Meaning |
| --- | --- | --- |
| `models_directory` | `/opt/philter-batch/models` | Where PhEye models are. A policy's `phEyeConfiguration.modelPath` that is a bare name (such as `ph-eye-pii-en-small`) is a subdirectory here. Every PhEye filter must name a local model: Philter Batch runs models in-process and calls no PhEye service. |
| `policy_files_directories` | none | Directories a policy may read files from (dictionaries, ignore lists). A policy naming a file elsewhere is refused. |

## Strategies and keys

Philter Batch runs any strategy Phileas accepts. Two need a key, and three weaken the output in a way the sample's
reviewer should see:

| Strategies | In Philter Batch |
| --- | --- |
| `REDACT`, `MASK`, `STATIC_REPLACE`, `TRUNCATE`, `TRUNCATE_TO_YEAR`, `ABBREVIATE`, `MAP_REPLACE`, `RANDOM_REPLACE`, `SHIFT` | Run as in Phileas. |
| `FPE_ENCRYPT_REPLACE`, `CRYPTO_REPLACE` | Keyed from the [pseudonymization secret](#pseudonymization-secret). A policy may not hold their key. |
| `CRYPTO_REPLACE`, `HASH_SHA256_REPLACE` without `salt`, `LAST_4` | Warned about by `set-policy`, the inventory's log, and the sample report. Not refused. |

The warnings:

- `CRYPTO_REPLACE` encrypts each value with AES-GCM and a random nonce: anyone with the key can recover the
  original, the same value gets a different replacement each time, and Philter Batch has no re-identify endpoint.
- `HASH_SHA256_REPLACE` without `salt: true` can be reversed by guessing: hashing a guessed value confirms it.
- `LAST_4` keeps the last four characters of each value.

### Consistency of replacements

Matching is on the exact string, so `Avery Example` and `A. Example` get different replacements.

- `RANDOM_REPLACE`, a `MAP_REPLACE` generator, and a random `SHIFT` give every occurrence of a value in one
  document the same replacement, with either `replacementScope` (`DOCUMENT`, the default, or `CONTEXT`), and a
  random `SHIFT` moves every date in the document by the same number of days. Each document gets its own
  replacements: Philter Batch gives each document its own context, so `CONTEXT` does not carry from one document
  to the next. A large plain-text file split into segments is redacted segment by segment, so each segment is a
  document here.
- `FPE_ENCRYPT_REPLACE` gives a value the same replacement in every document, on every worker, and in every run
  keyed from the same secret version, and no two values share one, with nothing stored per value. It fits
  identifiers such as SSNs and phone and account numbers. A value whose format-preserved content is outside 6 to 56
  characters, FF3-1's range in Phileas, is redacted instead.
  Runs keyed from different secrets, or different versions of one, give different replacements.
- `CRYPTO_REPLACE` gives a different replacement each time, even within one document.

### Pseudonymization secret

| Setting | Default | Meaning |
| --- | --- | --- |
| `pseudonymization.secret_arn` | none | The AWS Secrets Manager secret the keys come from, by its full ARN. Without it, a policy using `FPE_ENCRYPT_REPLACE` or `CRYPTO_REPLACE` is refused. In a deployment the chart sets it (`pseudonymization.secretArn`). |
| `pseudonymization.endpoint` | AWS | A Secrets Manager endpoint instead of AWS's, for testing. |

The secret holds one random value of at least 32 bytes: its binary value, or its string's UTF-8 bytes. The Terraform
can create one (`create_pseudonymization_secret`, see [AWS](aws.md#variables)); to make one yourself, store a random
value such as `openssl rand -base64 48`. Turn off automatic rotation: a new version gives later runs other keys, and
Philter Batch has no rotation function.

Philter Batch derives each key from the value with HKDF-SHA256 (RFC 5869), with no salt and one `info` label per
use, so a secret version always gives the same keys:

| Label | Bytes | Use |
| --- | --- | --- |
| `philter-batch/v1/fpe-key` | 32 | The FF3-1 key of `FPE_ENCRYPT_REPLACE` (AES-256). |
| `philter-batch/v1/fpe-tweak` | 7 | The FF3-1 tweak. |
| `philter-batch/v1/crypto-key` | 32 | The AES-256 key of `CRYPTO_REPLACE`. |
| `philter-batch/v1/fingerprint` | 32 | Recorded in `run.json` to check the key with. It reveals neither the value nor a key. |

How the key is handled:

- **Never in the policy.** A policy with an `fpe` or `crypto` block holding a key, tweak, or IV, whether literal,
  `env:NAME`, or `${NAME}`, is refused, so the stored `policy.json` never holds a key. Leave both blocks out.
  A policy may not name an environment variable at all: Phileas's policy environment is restricted to none.
- **Read once per process.** Each process that redacts (the sample, each worker, `run`) and the inventory calls
  `GetSecretValue` once, with the pod's IAM role, holds the value in memory, and sets the keys on the loaded
  policy. Requests grow with process starts, not with the number of documents. A policy without a keyed strategy
  reads no secret.
- **Pinned per run.** The inventory records the secret's ARN, the `VersionId` it read (the current one), and the
  fingerprint in `run.json`. Every later process of the run, a resumed one included, reads that version and checks
  the fingerprint, and stops before writing output if the deployment names another secret, the version cannot be
  read, or its value gives another fingerprint. A rotation during a run therefore cannot mix two keys, and a run
  whose version was deleted cannot resume.
- **Nowhere else.** The key and the value are not written to the bucket, logs, reports, `run.json`, the output,
  environment variables, or Kubernetes objects. The chart holds only the ARN.

To reverse a run's FPE values elsewhere, such as with Phileas or Philter, `fpe-key` prints the run's FPE key and
tweak as a policy's `fpe` block, to standard output only:

```sh
philter-batch fpe-key <run_id>        # in a deployment; or: philter-batch fpe-key -c run.yaml
```

It uses the version the run pinned, or the current version before the run starts. Anyone holding its output can
reverse the run's FPE values. In the EKS stack, the console's role reads the secret only with
`console_reads_pseudonymization_secret`; otherwise run `fpe-key` with credentials that can.

Deleting the secret (or the version a run pinned) makes that run's FPE and `CRYPTO_REPLACE` values irreversible,
and the run cannot be resumed. Secrets Manager keeps a deleted secret for its recovery window (7 to 30 days) first.

## Plain text

| Setting | Default | Meaning |
| --- | --- | --- |
| `plain_text.extensions` | `[txt, md, log]` | The extensions of plain text, compared ignoring case. An object whose extension names no handled type is recorded as skipped (`UNSUPPORTED_TYPE`) and never read or copied. |
| `plain_text.charset` | `UTF-8` | The input's character set. A file that does not decode in it is quarantined (`UNDECODABLE`). Document output is written in the same character set. |

## Input types

By default a run redacts plain text only. `input_types.handled` adds other types, each redacted with its Phileas
service.

| Setting | Default | Meaning |
| --- | --- | --- |
| `input_types.handled` | `[text]` | The types redacted: `text`, `csv`, `pdf`, `docx` (Word), `xlsx` (Excel), `rtf`, and `email` (MIME `.eml` and Outlook `.msg`). |
| `input_types.extensions` | each type's own | Extensions per type, replacing its defaults: `csv: [csv]`, `pdf: [pdf]`, `docx: [docx]`, `xlsx: [xlsx]`, `rtf: [rtf]`, `email: [eml, msg]`. Plain text's are `plain_text.extensions`. An extension may name one handled type only. |
| `input_types.max_document_bytes` | 256 MiB | Files of a type other than plain text larger than this are skipped (`TOO_LARGE`), as are files over `inventory.max_file_bytes`. Under 2 GiB. |

Inventory chooses files by extension and does not read them. In a run that handles a type other than plain text,
a worker detects each whole file's type from its content and redacts it as that type (a plain-text-only run reads
every file as text, as before):

- PDF by its `%PDF-` header, RTF by `{\rtf`, Word and Excel by Phileas's own check of the OPC package, and Outlook
  messages by the OLE2 signature, only for an email extension (OLE2 is also the format of legacy `.doc` and `.xls`
  files and of encrypted Word and Excel files, which are not handled).
- Plain text, CSV, and MIME email have no signature, so a file without one is the type its extension names.
- A file whose content is a different handled type than its extension names is redacted as its content, and
  `type_mismatch` is recorded in its result and the manifest. Its redacted document takes the content type's
  extension.
- A file whose content no handled type recognizes, such as a `.pdf` without a PDF header, a ZIP that is not a Word
  document or workbook, or PDF content in a run that does not handle `pdf`, is quarantined (`UNSUPPORTED_CONTENT`).

Detection uses the checks the Phileas services use, so a file goes only to a service that accepts its format.
Tika was not used: `tika-core` without its parsers cannot tell a Word document from an Excel workbook, which are
both ZIP files, and Word and Excel can only be told apart from the whole file, which is why detection happens in
the worker and not in inventory.

Each file of these types is one entry, read whole into memory: unlike plain text, it is not split. Size the
workers' memory for `worker.threads` files of up to `max_document_bytes` at once, plus Phileas's working copies.
Plain-text segments are always plain text, without detection.

In `text` output, a document's record holds its redacted text in sections, such as `page 1` or `body`, with where
each one is in the record's text ([record schema](record-schema.md)). In `document` output, the redacted file is
of the same type, except that an Outlook message is written as MIME (`.eml`), and a PDF's redacted pages are
images, so their text cannot be read back. A redacted CSV is written in the policy's encoding, and its content type
has no charset. A
CSV's delimiter, quote, and encoding come from the policy's `config.csv`, not from `plain_text.charset`.

Phileas's email setting `config.email.attachments` defaults to `remove`: attachments are left out of `text` output
and deleted from `document` output. Set it to `redact` in the policy to redact PDF, Word, Excel, RTF, CSV, and
plain-text attachments (and remove any other). `config.email.inlineImages` likewise defaults to `remove`.

> **Warning:** With `attachments: keep` or `inlineImages: keep`, they are left unread: in `text` output they are
> left out, and in `document` output they are copied into the redacted message unredacted.

A section Phileas finds no text in, such as a scanned page, is `no_text`: it is empty and not redacted. In `text`
output such sections are counted in the report (`sections_without_text`); in `document` output Phileas does not
report them, so they are not counted. OCR is not supported.

Phileas's own limits apply, at its defaults: a PDF of more than 2,000 pages and a CSV of more than 1,000,000 rows,
among others, are quarantined (`LIMIT_EXCEEDED`). A CSV is read whole, so a large one is better handled as plain
text, by listing `csv` in `plain_text.extensions` instead, where it is split into segments and redacted line by
line rather than by column.

Quarantine reasons for these types, besides those of plain text: `UNSUPPORTED_CONTENT`, `ENCRYPTED` (an encrypted
message or a password-protected PDF), `MALFORMED` (not valid in its format), and `LIMIT_EXCEEDED` (over one of
Phileas's limits, such as on pages or rows). Other Phileas failures are `REDACTION_FAILED`.

## Inventory

| Setting | Default | Meaning |
| --- | --- | --- |
| `inventory.shard_target_bytes` | 128 MiB | Input bytes per shard. |
| `inventory.max_entries_per_shard` | 10000 | The most files or segments in one shard, which bounds the size of its progress object. |
| `inventory.segment_bytes` | 16 MiB | Files larger than this are split into segments of about this size at line breaks, so one large file is processed in parallel. Only for character sets in which byte `0x0A` is always a newline. |
| `inventory.max_file_bytes` | 50 GiB | Larger files are skipped (`TOO_LARGE`). |
| `inventory.max_line_bytes` | 16 MiB | A segment whose last line runs this far past its end is quarantined (`LINE_TOO_LONG`). |

## Leases

| Setting | Default | Meaning |
| --- | --- | --- |
| `lease.initial_seconds` | 600 | The first lease on a shard. Each takeover after an expiry doubles it. |
| `lease.max_seconds` | 86400 | The longest lease. |
| `lease.renew_seconds` | 30 | How often a holder renews. At most a third of `initial_seconds`. |
| `lease.expiry_grace_seconds` | 5 | How long past its expiry a claim must be before it is taken over. |
| `lease.max_clock_skew_seconds` | 300 | The largest difference between a worker's clock and the store's that is accepted. Leases use the store's clock either way. |
| `lease.poison_attempts` | 3 | A file at which workers keep dying is quarantined (`POISON`) after this many expired leases without progress. |

## Worker

| Setting | Default | Meaning |
| --- | --- | --- |
| `worker.threads` | half the processors | Shards processed at once in one process. |
| `worker.onnx_threads` | processors / threads | ONNX Runtime's threads per model call. Set it explicitly under a CPU limit. |
| `worker.flush_bytes` | 8 MiB | Buffered JSONL that triggers uploading a part and saving progress. |
| `worker.flush_seconds` | 60 | The longest time between progress saves. A crash redoes at most this much work. |
| `worker.file_timeout_seconds` | 600 | The longest redaction of one file or segment; a longer one is quarantined (`TIMEOUT`). |
| `worker.shutdown_grace_seconds` | 25 | After SIGTERM, how long to wait for the files in hand before exiting. Set below the pod's `terminationGracePeriodSeconds`. |

## Sample

After the inventory, a sample of the input is redacted first, and the rest of the run waits until a person approves
it ([formats](formats.md#the-sample-and-the-gates)). The sample is random, spread across file types and sizes, and
chosen again identically if the inventory is rerun. Its entries are not redone in the rest of the run.

| Setting | Default | Meaning |
| --- | --- | --- |
| `sample.enabled` | `true` | Whether there is a sample and a sample gate. Turn it off for a policy already validated on this data. |
| `sample.size` | `50` | The most entries (files, or segments of large files) in the sample. |
| `sample.seed` | derived from `run_id` | The seed of the random choice. |
| `sample.report_max_chars` | `20000` | The most characters of each sampled entry shown in the HTML report. |
| `sample.price_per_worker_hour` | none | The price of one worker pod for an hour. When set, the report estimates the cost of the rest of the run. |
| `sample.currency` | `USD` | The price's currency, as shown. |

The report's throughput is measured on the sample and its estimate is for the deployment's worker pods
(`workers.count` in the Helm chart; one process for `run`). It leaves out pod start-up, model loading, retries, and
interrupted spot nodes.

## Release gate

| Setting | Default | Meaning |
| --- | --- | --- |
| `release_gate.enabled` | `true` | After finalize, the workflow waits until a person approves or rejects the output. `run` does not wait at it. |

## Quarantine limit

| Setting | Default | Meaning |
| --- | --- | --- |
| `quarantine.max_percent` | `5` | Quarantined entries (files, or segments of large files) as a percentage of all entries, from 0 to 100. Above it, `approve release` refuses unless given `--accept-quarantine`; without a release gate (`release_gate.enabled: false`, or `run`), finalize and `run` exit 6. |

The output of the entries that were not quarantined is written either way. In document and both modes, a
large file split into segments is withheld from `documents/` when any one of its segments is quarantined, but only
that segment counts toward the limit; `documents_withheld` in `report.json` counts the withheld files. The limit is read from the run's
configuration, which is fixed once the run starts. A release marker written by hand (for example with `aws s3 cp`)
is a decision without this check.

## In a deployment

A run's configuration stored with `configure` differs from a local one in two ways:

- The deployment sets `store`, `output.bucket`, and `output.prefix`. Leave them out, or give the deployment's
  values; different values are refused.
- The deployment sets `pseudonymization` too: leave it out, or give the deployment's value.
- `policy`, `worker.threads`, `worker.onnx_threads`, and `worker.shutdown_grace_seconds` are refused: `set-policy`
  stores the policy, and the chart passes the worker settings to each pod (`--threads`, `--onnx-threads`, and
  `--shutdown-grace-seconds`, which also work locally and replace the file's).

The configuration and policy can be replaced until the run starts (its `run.json` exists), and are fixed after.

The deployment file (`deployment.yaml`, written by the chart; named by `--deployment` or
`PHILTER_BATCH_DEPLOYMENT`):

```yaml
store: {type: s3, region: us-east-1}
output: {bucket: example-output-bucket, prefix: philter-batch/}
pseudonymization: {secret_arn: "arn:aws:secretsmanager:us-east-1:111122223333:secret:philter-batch-AbCdEf"}
```

## Leftover check

Philter Batch does not run Phileas's leftover check for now. Whatever a policy's `config.review.leftoverCheck`
says, workers turn it off, and `set-policy` and the worker log note that it is ignored. `leftover_checked` is
`false` on every result and the reports count none. `LEFTOVER_FOUND` remains a defined reason code for when the
check returns.

Redaction is probabilistic and does not catch every value. Review the sample report and measure the policy
against labeled data to judge what it misses.
