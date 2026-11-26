/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import ai.philterd.batch.config.RunConfig;

/**
 * The keys of everything a run writes, under {@code <output prefix>runs/<run_id>/}. See {@code docs/formats.md}.
 */
public final class RunLayout {

    private final String bucket;
    private final String root;

    public RunLayout(final RunConfig config) {
        this(config.output().bucket(), config.output().prefix(), config.runId());
    }

    public RunLayout(final String bucket, final String prefix, final String runId) {
        this.bucket = bucket;
        final String base = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        this.root = base + "runs/" + runId + "/";
    }

    public String bucket() {
        return bucket;
    }

    public String root() {
        return root;
    }

    /** A shard's ID: its number, zero-padded to six digits. */
    public static String shardId(final int number) {
        return String.format("%06d", number);
    }

    public String settings() {
        return root + "run.json";
    }

    public String policy() {
        return root + "policy.json";
    }

    public String inventory() {
        return root + "inventory.json";
    }

    public String skipped(final int number) {
        return root + "inventory/skipped-" + String.format("%06d", number) + ".jsonl";
    }

    public String skippedPrefix() {
        return root + "inventory/skipped-";
    }

    public String idKey() {
        return root + "manifest/id-key";
    }

    public String shardsPrefix() {
        return root + "shards/";
    }

    public String shard(final String id) {
        return shardsPrefix() + id + ".json";
    }

    public String claimsPrefix() {
        return root + "claims/";
    }

    public String claim(final String id) {
        return claimsPrefix() + id;
    }

    public String progressPrefix() {
        return root + "progress/";
    }

    public String progress(final String id) {
        return progressPrefix() + id;
    }

    public String donePrefix() {
        return root + "done/";
    }

    public String done(final String id) {
        return donePrefix() + id + ".json";
    }

    public String partsPrefix() {
        return root + "parts/";
    }

    public String part(final String shard, final String worker, final int sequence) {
        return partsPrefix() + "part-" + shard + "-" + worker + "-" + String.format("%05d", sequence) + ".jsonl";
    }

    public String stagingPrefix() {
        return root + "staging/";
    }

    /** A redacted document, or one segment of one, before finalize puts it in place. */
    public String staged(final String documentId, final int segment, final int segments, final String worker,
                         final String extension) {
        final String seg = segments > 1 ? "-s" + String.format("%05d", segment) : "";
        return stagingPrefix() + documentId + seg + "-" + worker + "." + extension;
    }

    public String documentsPrefix() {
        return root + "documents/";
    }

    public String document(final String documentId, final String extension) {
        return documentsPrefix() + documentId + "." + extension;
    }

    public String manifestPrefix() {
        return root + "manifest/";
    }

    public String manifestDocuments(final String shard) {
        return manifestPrefix() + "documents-" + shard + ".jsonl";
    }

    public String manifestSkipped() {
        return manifestPrefix() + "skipped.jsonl";
    }

    public String report() {
        return root + "report.json";
    }

    public String success() {
        return root + "_SUCCESS";
    }

    /** The run's configuration as stored by {@code configure}. */
    public String storedConfig() {
        return root + "config/run.yaml";
    }

    /** The run's policy as stored by {@code set-policy}. */
    public String storedPolicy() {
        return root + "config/policy.json";
    }

    public String samplePrefix() {
        return root + "sample/";
    }

    public String sampleReport() {
        return samplePrefix() + "report.json";
    }

    public String sampleReportHtml() {
        return samplePrefix() + "report.html";
    }

    public String sampleRecords() {
        return samplePrefix() + "records.jsonl";
    }

    /** A sampled entry's redacted document, in document mode. */
    public String sampleDocument(final String documentId, final int segment, final int segments,
                                 final String extension) {
        final String seg = segments > 1 ? "-s" + String.format("%05d", segment) : "";
        return samplePrefix() + "documents/" + documentId + seg + "." + extension;
    }

    public String gatesPrefix() {
        return root + "gates/";
    }

    /** A gate's marker: {@code gates/<gate>-approved.json} or {@code gates/<gate>-rejected.json}. */
    public String gateMarker(final String gate, final String decision) {
        return gatesPrefix() + gate + "-" + decision + ".json";
    }

    /** The shard ID in a key under one of the per-shard prefixes, such as {@code claims/000012}. */
    public static String shardOf(final String key) {
        final String name = key.substring(key.lastIndexOf('/') + 1);
        final int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

}
