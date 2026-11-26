# JSONL record schema

In `text` and `both` output modes, Philter Batch writes redacted text as JSON Lines under
`<output.prefix>runs/<run_id>/parts/` (and a copy of the sample's records in `sample/records.jsonl`), one record
per line, UTF-8, each line ending in `\n`. This page
specifies `schema_version` **1**. The Java record is `TextRecord` in
`src/main/java/ai/philterd/batch/run/Formats.java`.

```json
{"schema_version": 1, "id": "716979bb1f65e1c1ef5124bda44e129f", "document_id": "716979bb1f65e1c1ef5124bda44e129f",
 "segment": 0, "segments": 1, "type": "text",
 "text": "{{{REDACTED-name}}} was the first president. Write to {{{REDACTED-email-address}}}.\n",
 "pii_counts": {"email-address": 1, "person": 1}, "run_id": "model-run", "policy": "sha256:c2e4e4a4..."}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer | `1`. Fields may be added without a change; removing or redefining one raises it. |
| `id` | string | Unique within the run. The `document_id` for a whole file; for a segment of a split file, `document_id` + `.` + the segment number in five digits, such as `0d4f....00002`. |
| `document_id` | string | Opaque: 32 hex characters, the same for every segment of one input file. Keyed per run, so IDs from different runs cannot be joined. |
| `segment` | integer | The segment number, from 0. |
| `segments` | integer | How many segments the file was split into; 1 for a whole file. |
| `type` | string | The input's type. `text` in this version. |
| `text` | string | The redacted text. For a segment, the lines that start in its byte range, each through its newline, so a file's segments concatenated in order are the whole redacted file. |
| `pii_counts` | object | Values replaced, by Phileas filter type (such as `person`, `email-address`, `ssn`). Empty when nothing was replaced. |
| `run_id` | string | The run. |
| `policy` | string | `sha256:` and the SHA-256 of the policy file's bytes. |

What is not in a record:

- No input key, file name, or path: they often contain names. The mapping from `document_id` to the input key is
  only in `manifest/documents-NNNNNN.jsonl` under the run's prefix, which should not be given to the training
  pipeline.
- No detected values, offsets, or metadata of the input.
- No record for a quarantined or empty input. Its status and reason code are in the done markers, the manifest,
  and `report.json`.

Each value is replaced consistently within one record. The same value in different records, or in different
segments of one file, may get a different replacement, depending on the policy's strategies.

Redaction is probabilistic: a record can still contain sensitive values the policy did not detect. Validate
output against your own data before use.
