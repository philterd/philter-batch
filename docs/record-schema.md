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
| `type` | string | The type the input was redacted as, detected from its content: `text`, `csv`, `pdf`, `docx`, `xlsx`, `rtf`, or `email`. |
| `text` | string | The redacted text. For a segment, the lines that start in its byte range, each through its newline, so a file's segments concatenated in order are the whole redacted file. For another type, its sections' text, separated by a blank line. |
| `pii_counts` | object | Values replaced, by Phileas filter type (such as `person`, `email-address`, `ssn`). Empty when nothing was replaced. |
| `run_id` | string | The run. |
| `policy` | string | `sha256:` and the SHA-256 of the policy file's bytes. |
| `sections` | array | For a type other than plain text, where each section of the document is in `text`, in order (below). Absent for plain text. |

A section is `{"label": "page 1", "status": "text", "start": 0, "end": 1834}`: characters `start` to `end` of
`text`, in UTF-16 code units, end exclusive.

- `label`: what the section is, from Phileas, such as `page N` (PDF); `body`, `headers`, `footers`, `footnotes`
  (Word); `body`, `headers and footers` (RTF); `sheet N` (Excel); `rows` (CSV); `headers`, `body`,
  `attached message N / ...`, and `attachment N / ...` (email). The Phileas documentation for each format lists
  them all. Phileas's labels name sheets and attachments, which
  can hold names and are not redacted, so they are numbered instead, in the order they appear: `sheet 1`,
  `attachment 2 / page 1`.
- `status`: `text`; `no_text`, a section with no text that could be read, such as a scanned page, which is empty
  and was not redacted; or `not_scanned`, a section the policy left out, which is empty.

```json
{"schema_version": 1, "id": "0d4f...", "document_id": "0d4f...", "segment": 0, "segments": 1, "type": "pdf",
 "text": "Write to {{{REDACTED-email-address}}} about the order.",
 "pii_counts": {"email-address": 1}, "run_id": "model-run", "policy": "sha256:c2e4...",
 "sections": [{"label": "page 1", "status": "text", "start": 0, "end": 54},
              {"label": "page 2", "status": "no_text", "start": 54, "end": 54}]}
```

What is not in a record:

- No input key, file name, or path: they often contain names. The mapping from `document_id` to the input key is
  only in `manifest/documents-NNNNNN.jsonl` under the run's prefix, which should not be given to the training
  pipeline.
- No detected values, offsets, or metadata of the input.
- No record for a quarantined or empty input. Its status and reason code are in the done markers, the manifest,
  and `report.json`.

Whether a value gets the same replacement each time depends on the policy's strategies. `RANDOM_REPLACE` and a
random `SHIFT` are consistent within one record, with either `replacementScope`, and not across records or segments.
`FPE_ENCRYPT_REPLACE` is consistent across records and runs keyed from the same secret version. See
[Consistency of replacements](configuration.md#consistency-of-replacements).

Redaction is probabilistic: a record can still contain sensitive values the policy did not detect. Validate
output against your own data before use.
