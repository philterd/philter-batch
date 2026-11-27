# Run layout and coordination formats

Philter Batch keeps all of a run's state in the output bucket, beside its output. Workers coordinate only
through conditional writes to these objects: create-if-absent (`If-None-Match: *`) and replace-if-match
(`If-Match: <ETag>`). There is no database, queue, or other shared state, and no conditional delete is used
(MinIO accepts a `DeleteObject` with a non-matching `If-Match` and deletes the object).

This page specifies format version **1**. The Java records are in
`src/main/java/ai/philterd/batch/run/Formats.java`; change both together.

## Versioning

Every coordination object has a `format` field. A reader refuses an object whose `format` is newer than it
knows, so a run is never processed by a build that would misread it. Readers ignore fields they do not know, so
a field can be added without a version change. Removing or changing the meaning of a field requires a new
format version. All objects are JSON with snake_case keys and ISO-8601 UTC times; fields that are null are
left out.

## Layout

Everything is under `<output.prefix>runs/<run_id>/` in `output.bucket`:

```
config/run.yaml                                the run's configuration, stored by configure (deployments only)
config/policy.json                             the run's policy, stored by set-policy (deployments only)
run.json                                       settings the run was inventoried with
policy.json                                    the Phileas policy, as given to the inventory
inventory.json                                 inventory summary; written last, marks the inventory complete
inventory/skipped-NNNNNN.jsonl                 objects left out, with keys and reasons
shards/NNNNNN.json                             one shard each, written once
claims/NNNNNN                                  who holds a shard, until when
progress/NNNNNN                                a shard's cursor and committed results
done/NNNNNN.json                               created once when a shard is complete
counts/NNNNNN.json                             a completed shard's counts, for status
parts/part-NNNNNN-<worker>-NNNNN.jsonl         redacted text records (output)
staging/<document_id>[-sNNNNN]-<worker>.<ext>  redacted documents before finalize
documents/<document_id>.<ext>                  redacted documents (output)
manifest/id-key                                the run's secret for document IDs
manifest/documents-NNNNNN.jsonl                document ID to input key, with status (finalize)
manifest/skipped.jsonl                         every skipped object (finalize)
report.json                                    counts (finalize)
_SUCCESS                                       what produced the output, and counts (finalize)
sample/records.jsonl                           the sample's redacted text records (text and both modes)
sample/documents/<document_id>[-sNNNNN].<ext>  the sample's redacted documents (document and both modes)
sample/report.html                             the sample report, for people
sample/report.json                             the sample report; written last, marks the sample complete
gates/<gate>-approved.json                     a decision at a gate (sample or release)
gates/<gate>-rejected.json
```

Shard IDs are six-digit zero-padded numbers from `000000`. `<worker>` is the pod's host name, a random suffix,
and a thread number, such as `philter-batch-work-7xk2p-3f9a1c-t2`.

Only `parts/` and `documents/` are output for the training pipeline or the customer. Everything else, and
`manifest/` in particular, holds input keys, which can contain names: do not give it to the training pipeline.
`sample/` holds redacted text, a copy of output that is also in `parts/` or `documents/`, and no keys.

## run.json

```json
{"format": 1, "run_id": "pilot-2026-11", "output_mode": "text", "charset": "UTF-8",
 "extensions": ["txt", "md", "log"], "policy_sha256": "c2e4..."}
```

Written once by the inventory. Every worker compares its own configuration with it and refuses to start on a
difference, so all shards of a run are processed the same way.

## config/run.yaml and config/policy.json

In a deployment, `configure` stores the run's YAML configuration as given, and `set-policy` stores the policy
file's bytes, after checking each. Both can be replaced until `run.json` exists. Every pod of the run reads them by
run ID, with the deployment's store and output location filled in ([configuration](configuration.md#in-a-deployment)).
They are not versioned objects of their own: `run.yaml` is the configuration format, and `policy.json` is a Phileas
policy.

## policy.json

The policy file's bytes, written once by the inventory (from `config/policy.json` in a deployment). Workers load the policy from here, not from a local
file, and check its SHA-256 against `run.json`.

## inventory.json

```json
{"format": 1, "run_id": "pilot-2026-11", "completed": "2026-11-25T12:00:00Z", "shards": 412,
 "documents": 18734, "entries": 18790, "bytes": 52428800000,
 "by_extension": {"txt": {"objects": 18000, "bytes": 50000000000, "included": true},
                  "pdf": {"objects": 950, "bytes": 9100000000, "included": false}},
 "skipped_by_reason": {"UNSUPPORTED_TYPE": 950, "EMPTY_FILE": 12}, "skipped_files": 962,
 "sample_shards": 50, "sample_entries": 50, "sample_bytes": 2621440}
```

`sample_shards`, `sample_entries`, and `sample_bytes` describe the sample: shards `000000` to `sample_shards - 1`
are the sample's, one entry each. All three are 0 when the sample is turned off.

An extension that is not 1 to 10 letters or digits is counted as `(other)`; a key with none as `(none)`.
Skip reasons: `UNSUPPORTED_TYPE`, `TOO_LARGE`, `EMPTY_FILE`, `DIRECTORY_MARKER`.

The inventory can be run again after a crash: until `inventory.json` exists it rewrites the same shards from
the same listing (and deletes shard numbers past the new count); afterwards it does nothing.

## shards/NNNNNN.json

```json
{"format": 1, "run_id": "pilot-2026-11", "shard": "000012", "bytes": 134201221,
 "entries": [
   {"document_id": "716979bb1f65e1c1ef5124bda44e129f", "key": "in/notes/a.txt", "etag": "\"9b2c...\"",
    "size": 52011, "extension": "txt", "segment": 0, "segments": 1, "start": 0, "end": 52011},
   {"document_id": "0d4f...", "key": "in/logs/big.log", "etag": "\"77a1...-3\"", "size": 40000000,
    "extension": "log", "segment": 1, "segments": 3, "start": 16777216, "end": 33554432}]}
```

Written once and never changed. `document_id` is the first 128 bits of HMAC-SHA256 over
`bucket \0 key \0 etag`, keyed with `manifest/id-key`, in hex. It is the same for every segment of a file.

A file larger than `inventory.segment_bytes` is split into segments without being read: segment *i* covers
the byte range `[start, end)`, and holds the lines that **start** in that range, each through its newline. The
last line of a segment may run past `end`, by at most `inventory.max_line_bytes`. A worker reads a segment from
`start - 1` (to see whether a line starts exactly at `start`) to `end + max_line_bytes`. Segments are only made
for character sets in which byte `0x0A` is always a newline (UTF-8, ASCII, the ISO-8859 and Windows code pages);
files in other character sets are kept whole.

A worker reads an entry only if the input's ETag still matches `etag`; otherwise the entry is quarantined.

## claims/NNNNNN

```json
{"format": 1, "shard": "000012", "holder": "work-7xk2p-3f9a1c-t2", "attempt": 2, "revision": 17,
 "lease_seconds": 1200, "acquired": "2026-11-25T12:00:00Z", "expires": "2026-11-25T12:20:30Z",
 "cursor_at_acquire": 40, "stalled_attempts": 0, "released": false}
```

- `attempt`: one more than the number of leases on this shard that expired.
- `revision`: changes on every write. S3 gives identical bytes the same ETag, so without it a rewrite with the
  same content would not invalidate other workers' ETags.
- `lease_seconds`: `lease.initial_seconds` doubled once per expired attempt, capped at `lease.max_seconds`.
- `expires`: by the store's clock (below).
- `cursor_at_acquire`: the progress cursor when this holder took the shard.
- `stalled_attempts`: how many holders in a row ended by expiry with the cursor where they found it.
- `released`: the holder stopped cleanly (on SIGTERM) after saving progress.

## progress/NNNNNN

```json
{"format": 1, "shard": "000012", "holder": "work-7xk2p-3f9a1c-t2", "attempt": 2, "revision": 9,
 "cursor": 41, "results": [ ...document results for entries 0 to 40... ],
 "parts": ["philter-batch/runs/pilot-2026-11/parts/part-000012-work-7xk2p-3f9a1c-t2-00000.jsonl"]}
```

`cursor` is the next entry to process. `results` and `parts` hold everything committed before it.

A document result:

```json
{"entry": 3, "document_id": "716979bb1f65e1c1ef5124bda44e129f", "segment": 0, "segments": 1,
 "status": "quarantined", "reason": "UNDECODABLE", "leftover_checked": false, "record": false, "micros": 48211}
```

- `status`: `redacted`, `quarantined`, or `empty` (no text).
- `reason`, for `quarantined`: `CHANGED_SINCE_INVENTORY`, `INPUT_MISSING`, `UNDECODABLE`, `LINE_TOO_LONG`,
  `REDACTION_FAILED`, `TIMEOUT`, `LEFTOVER_FOUND`, `ENCODING_FAILED`, `POISON`, or `SEGMENT_QUARANTINED`.
- `pii_counts`: values replaced, by Phileas filter type. `leftover_counts`: what Phileas's leftover check still
  found, by filter type, when that quarantined the entry. Neither ever holds a value.
- `leftover_checked`: whether the leftover check ran on the entry. The report counts these as `leftover_checked`. The check is not run for now (see [configuration](configuration.md#leftover-check)), so it is `false`.
- `record`: whether a JSONL record was written. `staged`: the key of the staged document, in document mode.
- `micros`: how long the entry took to read, redact, and check, in microseconds. Absent for a `POISON` entry.
- `replacements`: for sample entries only, where each replacement is in the redacted text, by type:
  `[{"start": 9, "end": 37, "type": "email-address"}]`, in UTF-16 code units, end exclusive. Absent when Phileas's
  ranges could not be matched to its replacements exactly. Never holds a value.

## done/NNNNNN.json

```json
{"format": 1, "run_id": "pilot-2026-11", "shard": "000012", "holder": "work-7xk2p-3f9a1c-t2", "attempt": 2,
 "completed": "2026-11-25T12:31:02Z", "policy_sha256": "c2e4...", "phileas_version": "4.6.0",
 "pheye_onnx_version": "4.6.0", "models": [{"name": "ph-eye-pii-en-small", "onnx_sha256": "0ec8..."}],
 "output_mode": "text", "results": [ ... ], "parts": [ ... ]}
```

Created if absent, from the final progress. Finalize refuses a run whose done markers disagree on the policy,
the Phileas versions, or the models.

## counts/NNNNNN.json

```json
{"format": 1, "run_id": "pilot-2026-11", "shard": "000012", "entries": 9950,
 "entries_by_status": {"redacted": 9941, "quarantined": 9}, "quarantined_by_reason": {"UNDECODABLE": 9}}
```

Written by the worker that created the shard's done marker, after creating it, so that `status` can count
quarantines during a run by reading one small object per completed shard. It holds counts only. A shard whose done
marker exists without one (the worker stopped in between) is counted from its done marker. Finalize does not read
it.

## report.json

The run's counts, written by finalize, and repeated as `counts` in `_SUCCESS`: shards, documents, and entries;
`entries_by_status`, `quarantined_by_reason`, `skipped_by_reason`, and `pii_counts`; records and documents written,
and documents withheld. `quarantined_percent` is quarantined entries over all entries, `quarantine_max_percent` the
run's `quarantine.max_percent`, and `quarantine_limit_exceeded` whether the first is above the second. Reports
written before the limit existed lack these three.

## The sample and the gates

**Choosing the sample.** The inventory groups the entries by extension and by size band (each band 16 times the
size of the one below), puts the groups in a random order, and takes one entry from each group in turn, at random
within the group, until `sample.size` entries are chosen, so every type and size gets a place before any gets two.
The random choice is seeded with `sample.seed` (default: from the run ID), so a rerun of the inventory chooses the
same entries. Each sampled entry is its own shard, numbered first.

**The sample step** (`sample`) runs a worker that claims only the sample's shards, with the usual protocol, and
records `micros` and `replacements` in their results. When every sample shard is done, it writes `sample/`:
`records.jsonl` (the sample's JSONL records, as in `parts/`) or `documents/` (copies of the staged documents), then
`report.html`, then `report.json`. The rest of the run treats the sample's shards like any other: they have done
markers, so they are not redone, and finalize includes their output.

### sample/report.json

```json
{"format": 1, "run_id": "pilot-2026-11", "completed": "2026-11-26T12:00:00Z", "policy_sha256": "c2e4...",
 "phileas_version": "4.6.0", "pheye_onnx_version": "4.6.0",
 "models": [{"name": "ph-eye-pii-en-small", "onnx_sha256": "0ec8..."}], "output_mode": "text", "seed": -81264,
 "entries": 50, "documents": 50, "bytes": 2621440, "entries_by_extension": {"log": 12, "txt": 38},
 "entries_by_status": {"redacted": 48, "quarantined": 2}, "quarantined_by_reason": {"UNDECODABLE": 2},
 "pii_counts": {"email-address": 31, "person": 240}, "leftover_counts": {}, "leftover_checked": 0,
 "throughput": {"bytes": 2621440, "processing_seconds": 412.5, "bytes_per_second_per_thread": 6354.9,
                "threads": 1, "bytes_per_second_per_pod": 6354.9},
 "estimate": {"remaining_bytes": 52425000000, "remaining_entries": 18740, "worker_pods": 8, "hours": 286.6,
              "worker_hours": 2292.6, "price_per_worker_hour": 0.68, "currency": "USD", "cost": 1559.0},
 "samples": [{"id": "716979bb1f65e1c1ef5124bda44e129f", "document_id": "716979bb1f65e1c1ef5124bda44e129f",
              "segment": 0, "segments": 1, "extension": "txt", "bytes": 52011, "status": "redacted",
              "pii_counts": {"person": 4}, "leftover_checked": true, "micros": 8231544}]}
```

- `throughput`: `bytes` over the summed `micros` of the sampled entries is the rate per thread; times `threads`
  (the sample pod's, the same as a worker's) is the rate per pod. Absent if no entry was timed.
- `estimate`: the inventory's bytes and entries less the sample's, at that rate, over `worker_pods` pods. `cost`
  and `price_per_worker_hour` are present when the configuration sets `sample.price_per_worker_hour`.
- `samples`: one per sampled entry, with no input key or file name. `output` names its sample document, in document
  and both modes.

`report.html` is the same report for people: each sampled entry's redacted text, with the replacements highlighted
by type, and the counts, throughput, and estimate. It holds redacted text only, never an original value or an input
key, and is self-contained: its content security policy forbids scripts and any request.

### gates/

A gate is a point where the run waits for a person: `sample` (after the sample; on unless `sample.enabled` is
false) and `release` (after finalize; on unless `release_gate.enabled` is false). A decision is an object at
`gates/<gate>-approved.json` or `gates/<gate>-rejected.json`. Its presence is the decision, so it can be written with
any tool that can write to the bucket. `approve` and `reject` write it if absent, with a record:

```json
{"format": 1, "run_id": "pilot-2026-11", "gate": "sample", "decision": "approved",
 "decided": "2026-11-26T15:04:05Z", "by": "Avery Example", "note": "spot-checked",
 "sample_report_sha256": "9f2c..."}
```

- A rejection wins when both exist.
- `approve sample` needs `sample/report.json`; `approve release` needs `_SUCCESS`. A rejection needs neither.
- `sample_report_sha256` is the SHA-256 of the `report.html` that was reviewed (sample gate only).
- `approve release` refuses a run whose `report.json` is above `quarantine.max_percent` unless given
  `--accept-quarantine`, and then records `"quarantine_accepted": true`. A marker written by hand skips this check.
- `work` refuses to start while the run has a sample that is not approved.

## The protocol

**Store clock.** Every lease time is the store's: a worker keeps the offset between its own clock and the
`Date` header of the store's responses, and computes `now` as its clock plus that offset. A worker whose clock
differs from the store's by more than `lease.max_clock_skew_seconds` refuses to run.

**Claim.** A worker lists `shards/`, `done/`, and `claims/`, and goes through the shards without a done marker,
starting at a random one. It first tries shards with no claim, creating the claim if absent; on 412 another
worker won and it moves on. Then it tries existing claims: a claim that is `released`, or whose `expires` plus
`lease.expiry_grace_seconds` has passed, is replaced with `If-Match` on its ETag. Taking an expired claim adds
one to `attempt` (doubling the lease) and to `stalled_attempts` if the progress cursor equals
`cursor_at_acquire`, or resets `stalled_attempts` to 0 if it moved. Taking a released claim changes neither.

**Renew.** The holder rewrites its claim with a new `expires` every `lease.renew_seconds`, with `If-Match`. A
412 means another worker took the shard: the holder stops writing for it.

**Fence.** After claiming, the holder rewrites `progress/NNNNNN` with its own name, conditioned on the ETag it
just read (or creates it if absent). From then on it writes progress only with `If-Match` on the ETag of its
own last write, so a previous holder that lost its lease without noticing cannot overwrite it. If a previous
holder writes between the read and the write, the fence reads again and keeps that progress.

**Process.** From the cursor, each entry is read, decoded and redacted, or quarantined.
Records are buffered; every `worker.flush_bytes` or `worker.flush_seconds`, the buffer is uploaded as a new part
and progress is written with the new cursor, the results, and the part's key. Before each entry and each write,
the holder checks that its lease has not run out by the store's clock.

**Poison entries.** If `stalled_attempts` is above 0, the holder writes progress after every entry, so the
cursor stops exactly at an entry that kills workers. When `stalled_attempts` reaches `lease.poison_attempts`,
the entry at the cursor is recorded as quarantined (`POISON`) without being read, the cursor moves past it, and
`stalled_attempts` is reset.

**Done.** When the cursor reaches the end, the holder writes the final progress, then creates
`done/NNNNNN.json` if absent. A worker that takes over a shard whose cursor is already at the end only creates
the done marker. If two workers both finished a shard, only one done marker exists, and finalize deletes the
other's parts.

**Stop.** On SIGTERM a worker finishes the entry in hand, writes progress, and releases its claim
(`released: true`, `expires` now), so another worker can take the shard at once. It exits with code 3.

**Finalize.** Once every shard has a done marker: deletes parts no done marker names; for each document in
document mode whose entries were all redacted or empty, copies its staged pieces, in segment order, to
`documents/<document_id>.<ext>` (server-side where the store allows); deletes `staging/`; writes the manifest,
`report.json`, and `_SUCCESS`. It can be run again after a crash.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | `work`: no work left. `await-gate`: decided, either way (the decision is on standard output). Other commands: done. |
| 1 | Error: configuration, policy, store, clock difference, a settings mismatch with the run, or `work` before the sample is approved. |
| 2 | Command-line usage error. |
| 3 | Stopped by SIGTERM or SIGINT, with progress saved. Work remains. |
| 4 | `work`: work remains, but every remaining shard is claimed by another worker. |
| 5 | `finalize`: not every shard has a done marker yet. `sample`: not every sample shard has one. |
| 6 | `finalize` and `run`: finalized, but the quarantined entries are above `quarantine.max_percent`, and no release gate holds the output for a person (`run` never waits at it; `finalize` when `release_gate.enabled` is false). Again on every rerun. |
