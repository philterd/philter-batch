/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The versioned formats of a run's coordination objects. Each carries {@code format}; a reader refuses a newer
 * format than it knows. The specification, with examples, is {@code docs/formats.md}; keep the two in step.
 */
public final class Formats {

    /** The format version this build writes, for every object below. */
    public static final int FORMAT = 1;

    private Formats() {
    }

    /**
     * One file, or one segment of a large file, in a shard.
     * @param documentId The opaque ID used in all output in place of the key.
     * @param key The input object's key.
     * @param etag The input's ETag at inventory; a worker reads only that version.
     * @param size The whole object's size.
     * @param extension The lowercased extension, used to name document output.
     * @param segment This segment's index, from 0. A whole file is segment 0 of 1.
     * @param segments The number of segments of the file.
     * @param start The segment's first byte. The segment is the lines that start in {@code [start, end)}.
     * @param end The byte after the segment's nominal end.
     */
    public record ShardEntry(String documentId, String key, String etag, long size, String extension, int segment,
                             int segments, long start, long end) {

        public boolean whole() {
            return segments == 1;
        }
    }

    /** {@code shards/NNNNNN.json}: written once by inventory, never changed. */
    public record Shard(int format, String runId, String shard, long bytes, List<ShardEntry> entries) {
    }

    /**
     * {@code claims/NNNNNN}: who holds a shard, until when by the store's clock.
     * @param attempt One more than the number of leases on the shard that expired.
     * @param revision Changes on every write, so the ETag does (S3's ETag of identical bytes is identical).
     * @param leaseSeconds This holder's lease length: the initial lease doubled for each expired one.
     * @param cursorAtAcquire The progress cursor when this holder took the shard, to tell whether it moved.
     * @param stalledAttempts How many holders in a row ended (by expiry) with the cursor where they found it.
     * @param released The holder stopped (on SIGTERM) after saving progress; the shard can be taken at once.
     */
    public record Claim(int format, String shard, String holder, int attempt, long revision, long leaseSeconds,
                        Instant acquired, Instant expires, int cursorAtAcquire, int stalledAttempts, boolean released) {
    }

    /**
     * What happened to one entry.
     * @param status {@code redacted}, {@code quarantined}, or {@code empty}.
     * @param reason The reason code when quarantined.
     * @param piiCounts Values replaced, by filter type.
     * @param leftoverCounts Leftover-check findings, by filter type, when they quarantined the entry.
     * @param leftoverChecked Whether Phileas's leftover check ran on the entry.
     * @param record Whether a JSONL record was written.
     * @param staged The key of the staged redacted document, when one was written.
     * @param micros How long the entry took to read, redact, and check, in microseconds. Absent for an entry
     *               quarantined without being read ({@code POISON}).
     * @param replacements Where the replacements are in the redacted text, by type. Recorded for sample entries
     *                     only, for the sample report. Never holds a value.
     * @param type The type the entry was redacted as, detected from its content, when it is not plain text.
     *             Absent for plain text, and for an entry quarantined before its type was known.
     * @param typeMismatch Whether the content's type differs from the type its extension names.
     * @param sectionsWithoutText Sections (such as pages) Phileas found no text in, such as scanned pages, which
     *                            were not redacted. Absent for plain text and when there were none.
     */
    public record DocumentResult(int entry, String documentId, int segment, int segments, String status,
                                 String reason, Map<String, Integer> piiCounts, Map<String, Integer> leftoverCounts,
                                 Boolean leftoverChecked, boolean record, String staged, Long micros,
                                 List<Replacement> replacements, String type, Boolean typeMismatch,
                                 Integer sectionsWithoutText) {

        /** This result with how long the entry took. */
        public DocumentResult withMicros(final long elapsed) {
            return new DocumentResult(entry, documentId, segment, segments, status, reason, piiCounts, leftoverCounts,
                    leftoverChecked, record, staged, elapsed, replacements, type, typeMismatch, sectionsWithoutText);
        }

        /** This result with the sample's replacement positions. */
        public DocumentResult withReplacements(final List<Replacement> positions) {
            return new DocumentResult(entry, documentId, segment, segments, status, reason, piiCounts, leftoverCounts,
                    leftoverChecked, record, staged, micros, positions, type, typeMismatch, sectionsWithoutText);
        }

        public static final String REDACTED = "redacted";
        public static final String QUARANTINED = "quarantined";
        public static final String EMPTY = "empty";
    }

    /**
     * Where one replacement is in a redacted text: characters (UTF-16 code units) {@code start} to {@code end},
     * exclusive, and the Phileas filter type that put it there.
     */
    public record Replacement(int start, int end, String type) {
    }

    /**
     * {@code progress/NNNNNN}: the cursor within a shard, and everything committed before it.
     * @param cursor The next entry to process.
     * @param results The results of entries before the cursor.
     * @param parts The JSONL parts holding those entries' records.
     */
    public record Progress(int format, String shard, String holder, int attempt, long revision, int cursor,
                           List<DocumentResult> results, List<String> parts) {
    }

    /** {@code done/NNNNNN.json}: created once, if absent, when a shard is complete. */
    public record DoneMarker(int format, String runId, String shard, String holder, int attempt, Instant completed,
                             String policySha256, String phileasVersion, String pheyeOnnxVersion,
                             List<ModelInfo> models, String outputMode, List<DocumentResult> results,
                             List<String> parts) {
    }

    /**
     * {@code counts/NNNNNN.json}: a completed shard's counts, written by the worker that created its done marker,
     * after creating it, so that {@code status} can count quarantines during a run without reading every done marker.
     * A shard whose done marker exists without one (the worker stopped in between) is counted from its done marker.
     * @param entries The shard's entries.
     * @param entriesByStatus Entries by {@code redacted}, {@code quarantined}, or {@code empty}.
     * @param quarantinedByReason Quarantined entries by reason code.
     */
    public record ShardCounts(int format, String runId, String shard, long entries, Map<String, Long> entriesByStatus,
                              Map<String, Long> quarantinedByReason) {

        /** The counts of a shard's results. */
        public static ShardCounts of(final String runId, final String shard, final List<DocumentResult> results) {
            final Map<String, Long> byStatus = new TreeMap<>();
            final Map<String, Long> byReason = new TreeMap<>();
            for (final DocumentResult r : results) {
                byStatus.merge(r.status(), 1L, Long::sum);
                if (DocumentResult.QUARANTINED.equals(r.status()) && r.reason() != null) {
                    byReason.merge(r.reason(), 1L, Long::sum);
                }
            }
            return new ShardCounts(FORMAT, runId, shard, results.size(), byStatus, byReason);
        }
    }

    /**
     * The settings a run was inventoried with. Every worker checks its own configuration against them.
     * @param types The handled types and their extensions. Absent when the run handles plain text only.
     */
    public record RunSettings(int format, String runId, String outputMode, String charset,
                              List<String> extensions, String policySha256, Map<String, List<String>> types) {
    }

    /** Counts and bytes for one extension in the input. */
    public record TypeCount(long objects, long bytes, boolean included) {
    }

    /**
     * {@code inventory.json}: written last by inventory, so its presence means the shards are complete.
     * @param sampleShards The sample's shards, numbered first ({@code 000000} up), one entry each; 0 when the
     *                     sample is turned off. Absent from runs inventoried before the sample existed.
     * @param sampleEntries The entries in the sample.
     * @param sampleBytes The input bytes of the sample's entries.
     */
    public record InventorySummary(int format, String runId, Instant completed, int shards, long documents,
                                   long entries, long bytes, Map<String, TypeCount> byExtension,
                                   Map<String, Long> skippedByReason, int skippedFiles, int sampleShards,
                                   long sampleEntries, long sampleBytes) {
    }

    /** One line of {@code inventory/skipped-NNNNNN.jsonl}. */
    public record SkippedObject(String key, long size, String extension, String reason) {
    }

    /** One line of {@code manifest/documents-NNNNNN.jsonl}: the only place an output ID meets its key. */
    public record ManifestEntry(String documentId, String key, String etag, long size, int segment, int segments,
                                String status, String reason, String document, String type, Boolean typeMismatch) {
    }

    /**
     * {@code report.json} and the counts in {@code _SUCCESS}.
     * @param quarantinedPercent Quarantined entries as a percentage of all entries.
     * @param quarantineMaxPercent The run's {@code quarantine.max_percent}. Absent from reports written before it
     *                             existed, as are the other two.
     * @param quarantineLimitExceeded Whether {@code quarantinedPercent} is above {@code quarantineMaxPercent}.
     * @param entriesByType Entries redacted, by the type they were redacted as. Absent for a plain-text-only run.
     * @param typeMismatches Entries whose content's type differed from their extension's. Absent likewise.
     * @param sectionsWithoutText Sections Phileas found no text in, which were not redacted. Absent likewise.
     * @param entriesWithSectionsWithoutText Entries with at least one such section. Absent likewise.
     */
    public record Report(int format, String runId, Instant completed, String outputMode, int shards,
                         long documents, long entries, Map<String, Long> entriesByStatus,
                         Map<String, Long> quarantinedByReason, Map<String, Long> skippedByReason,
                         Map<String, Long> piiCounts, long leftoverChecked, long records, long documentsWritten,
                         long documentsWithheld, long partsDeleted, long stagedDeleted, Double quarantinedPercent,
                         Double quarantineMaxPercent, Boolean quarantineLimitExceeded,
                         Map<String, Long> entriesByType, Long typeMismatches, Long sectionsWithoutText,
                         Long entriesWithSectionsWithoutText) {
    }

    /** {@code _SUCCESS}: what produced the output. */
    public record Success(int format, String runId, Instant completed, String policySha256, String phileasVersion,
                          String pheyeOnnxVersion, List<ModelInfo> models, String imageDigest, String outputMode,
                          Report counts) {
    }

    /**
     * {@code gates/<gate>-approved.json} or {@code gates/<gate>-rejected.json}: a person's decision at a gate. The
     * object's presence is the decision; its content is a record of it, and a marker written by hand (for example
     * with {@code aws s3 cp}) need not be this JSON.
     * @param sampleReportSha256 For the sample gate, the SHA-256 of the {@code sample/report.html} that was approved
     *                           or rejected.
     * @param quarantineAccepted For a release approved although the run's quarantines exceeded
     *                           {@code quarantine.max_percent}: true. Absent otherwise.
     */
    public record GateDecision(int format, String runId, String gate, String decision, Instant decided, String by,
                               String note, String sampleReportSha256, Boolean quarantineAccepted) {
    }

    /** One sampled entry in {@code sample/report.json}. Carries no input key, file name, or value. */
    public record SampleEntry(String id, String documentId, int segment, int segments, String extension, long bytes,
                              String status, String reason, Map<String, Integer> piiCounts,
                              Map<String, Integer> leftoverCounts, Boolean leftoverChecked, Long micros,
                              String output) {
    }

    /**
     * The measured throughput of the sample.
     * @param bytesPerSecondPerThread Input bytes over the summed processing time of the sampled entries.
     * @param threads Shards processed at once in one worker pod.
     * @param bytesPerSecondPerPod {@code bytesPerSecondPerThread} times {@code threads}.
     */
    public record Throughput(long bytes, double processingSeconds, double bytesPerSecondPerThread, int threads,
                             double bytesPerSecondPerPod) {
    }

    /**
     * The estimated time and cost of the rest of the run.
     * @param workerPods The worker pods the estimate is for.
     * @param hours Wall-clock hours for the remaining bytes at the measured throughput.
     * @param pricePerWorkerHour From the configuration; absent when not given, and then so is {@code cost}.
     */
    public record Estimate(long remainingBytes, long remainingEntries, int workerPods, double hours,
                           double workerHours, Double pricePerWorkerHour, String currency, Double cost) {
    }

    /** {@code sample/report.json}: the sample's results, written last by the sample step. */
    public record SampleReport(int format, String runId, Instant completed, String policySha256,
                               String phileasVersion, String pheyeOnnxVersion, List<ModelInfo> models,
                               String outputMode, long seed, int entries, long documents, long bytes,
                               Map<String, Long> entriesByExtension, Map<String, Long> entriesByStatus,
                               Map<String, Long> quarantinedByReason, Map<String, Long> piiCounts,
                               Map<String, Long> leftoverCounts, long leftoverChecked, Throughput throughput,
                               Estimate estimate, List<SampleEntry> samples) {
    }

    /** A PhEye model the policy names: its directory name and the SHA-256 of its ONNX file. */
    public record ModelInfo(String name, String onnxSha256) {
    }

    /**
     * One line of a JSONL part: a redacted text. See {@code docs/record-schema.md}.
     * @param sections Where each of a document's sections (pages, sheets, body, attachments) is in {@code text}.
     *                 Absent for plain text.
     */
    public record TextRecord(int schemaVersion, String id, String documentId, int segment, int segments, String type,
                             String text, Map<String, Integer> piiCounts, String runId, String policy,
                             List<Section> sections) {
    }

    /**
     * One section of a document's redacted text: characters {@code start} to {@code end} of the record's text,
     * exclusive.
     * @param label Phileas's label, such as {@code page 1}, {@code body}, {@code sheet: People}, or
     *              {@code attachment: report.pdf / page 1}. Labels can hold names from the document, such as a
     *              sheet's or an attachment's, which are not redacted.
     * @param status {@code text}, {@code no_text} (nothing could be read, such as a scanned page; empty and not
     *               redacted), or {@code not_scanned} (left out by the policy).
     */
    public record Section(String label, String status, int start, int end) {
    }

}
