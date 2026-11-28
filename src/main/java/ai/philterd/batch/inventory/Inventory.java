/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.KeyPin;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Formats.SkippedObject;
import ai.philterd.batch.run.Formats.TypeCount;
import ai.philterd.batch.run.Hashes;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.PreconditionFailedException;
import ai.philterd.batch.store.StoredObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Lists the input and writes the run's shards. Safe to run again after a crash: it rewrites the same shards from
 * the same listing until {@code inventory.json} exists, and after that does nothing.
 */
public final class Inventory {

    private static final Logger LOGGER = LogManager.getLogger(Inventory.class);

    /** Lines per skipped-objects file. */
    static final int SKIPPED_PER_FILE = 10_000;

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final Clock clock;

    public Inventory(final ObjectStore store, final RunConfig config, final Clock clock) {
        this.store = store;
        this.config = config;
        this.layout = new RunLayout(config);
        this.clock = clock;
    }

    /**
     * Runs the inventory, or returns the existing one.
     * @param policyJson The policy file's bytes, stored with the run so every worker uses the same policy.
     */
    public InventorySummary run(final byte[] policyJson) {
        return run(policyJson, null);
    }

    /**
     * @param key The pseudonymization secret's version to pin in {@code run.json}, or null when the policy needs no
     *            key.
     */
    public InventorySummary run(final byte[] policyJson, final KeyPin key) {
        final Optional<StoredObject> existing = store.get(layout.bucket(), layout.inventory());
        if (existing.isPresent()) {
            final InventorySummary summary = Json.read(existing.get().data(), InventorySummary.class);
            ai.philterd.batch.run.FormatException.check("inventory.json", summary.format(), Formats.FORMAT);
            LOGGER.info("The inventory for run {} already exists: {} shards.", config.runId(), summary.shards());
            return summary;
        }

        final String policySha256 = Hashes.sha256(policyJson);
        putOnce(layout.policy(), policyJson, "application/json", "a different policy");
        putOnce(layout.settings(), Json.write(settings(config, policySha256, key)), "application/json", null);
        final byte[] secret = idSecret();

        final Classifier classifier = new Classifier(config);
        final boolean splittable = Classifier.splittable(config.plainText().charsetValue());
        final RunConfig.Inventory limits = config.inventory();

        final List<ShardEntry> entries = new ArrayList<>();
        final List<SkippedObject> skipped = new ArrayList<>();
        final Map<String, long[]> byExtension = new TreeMap<>();
        final Map<String, Long> skippedByReason = new TreeMap<>();
        long documents = 0;
        long bytes = 0;

        for (final ObjectInfo object : store.list(config.input().bucket(), config.input().prefix())) {
            if (Classifier.isDirectoryMarker(object.key())) {
                skip(skipped, skippedByReason, object, Classifier.NONE, SkipReason.DIRECTORY_MARKER);
                continue;
            }
            final String extension = Classifier.extension(object.key());
            final Optional<DocumentType> type = classifier.type(extension);
            final long[] count = byExtension.computeIfAbsent(extension, e -> new long[2]);
            count[0]++;
            count[1] += object.size();
            if (type.isEmpty()) {
                skip(skipped, skippedByReason, object, extension, SkipReason.UNSUPPORTED_TYPE);
            } else if (object.size() == 0) {
                skip(skipped, skippedByReason, object, extension, SkipReason.EMPTY_FILE);
            } else if (object.size() > limits.maxFileBytes() || (type.get() != DocumentType.TEXT
                    && object.size() > config.inputTypes().maxDocumentBytes())) {
                skip(skipped, skippedByReason, object, extension, SkipReason.TOO_LARGE);
            } else {
                documents++;
                bytes += object.size();
                final String id = Hashes.documentId(secret, config.input().bucket(), object.key(), object.etag());
                // Only plain text is split: every other type is redacted whole.
                final long segmentBytes = type.get() == DocumentType.TEXT && splittable ? limits.segmentBytes()
                        : Long.MAX_VALUE;
                entries.addAll(segments(id, object, extension, segmentBytes));
            }
        }

        // The sample, if any, comes first: one entry per shard, so the sample's worker threads can share it, and
        // shards 000000 to sample_shards - 1 are the sample's. The rest of the entries are packed as usual.
        final List<List<ShardEntry>> plan = new ArrayList<>();
        final List<ShardEntry> rest;
        long sampleBytes = 0;
        if (config.sample().enabled()) {
            final long seed = sampleSeed(config);
            final List<Integer> chosen = SampleSelector.select(entries, config.sample().size(), seed);
            final boolean[] sampled = new boolean[entries.size()];
            for (final int i : chosen) {
                sampled[i] = true;
                plan.add(List.of(entries.get(i)));
                sampleBytes += entries.get(i).end() - entries.get(i).start();
            }
            rest = new ArrayList<>(entries.size() - chosen.size());
            for (int i = 0; i < entries.size(); i++) {
                if (!sampled[i]) {
                    rest.add(entries.get(i));
                }
            }
        } else {
            rest = entries;
        }
        final int sampleShards = plan.size();
        plan.addAll(ShardPlanner.plan(rest, limits.shardTargetBytes(), limits.maxEntriesPerShard()));
        for (int i = 0; i < plan.size(); i++) {
            final String id = RunLayout.shardId(i);
            final long shardBytes = plan.get(i).stream().mapToLong(e -> e.end() - e.start()).sum();
            store.put(layout.bucket(), layout.shard(id),
                    Json.write(new Shard(Formats.FORMAT, config.runId(), id, shardBytes, plan.get(i))), "application/json");
        }
        // Shards left by an earlier, interrupted inventory of a listing that has since shrunk.
        for (final ObjectInfo old : store.list(layout.bucket(), layout.shardsPrefix())) {
            if (Integer.parseInt(RunLayout.shardOf(old.key())) >= plan.size()) {
                store.delete(layout.bucket(), old.key());
            }
        }
        writeSkipped(skipped);

        final Map<String, TypeCount> types = new TreeMap<>();
        byExtension.forEach((ext, c) -> types.put(ext, new TypeCount(c[0], c[1], classifier.isHandled(ext))));
        final InventorySummary summary = new InventorySummary(Formats.FORMAT, config.runId(), clock.instant(),
                plan.size(), documents, entries.size(), bytes, types, skippedByReason, skipped.size(), sampleShards,
                sampleShards, sampleBytes);
        try {
            store.putIfAbsent(layout.bucket(), layout.inventory(), Json.write(summary), "application/json");
        } catch (final PreconditionFailedException e) {
            // Another inventory of the same run finished first, from the same listing.
            LOGGER.warn("Another inventory of run {} finished first; using it.", config.runId());
            return Json.read(store.get(layout.bucket(), layout.inventory()).orElseThrow().data(), InventorySummary.class);
        }
        LOGGER.info("Inventory of run {}: {} documents ({} bytes) in {} entries and {} shards, of which {} are the "
                + "sample; {} objects skipped {}.", config.runId(), documents, bytes, entries.size(), plan.size(),
                sampleShards, skipped.size(), skippedByReason);
        return summary;
    }

    /** The sample's seed: the configured one, or one derived from the run ID. */
    public static long sampleSeed(final RunConfig config) {
        return config.sample().seed() != null ? config.sample().seed() : SampleSelector.defaultSeed(config.runId());
    }

    /** The settings recorded with a run, which workers check their configuration against. */
    public static RunSettings settings(final RunConfig config, final String policySha256, final KeyPin key) {
        // Recorded only beyond plain text, so a plain-text run's run.json is what it was before other types.
        Map<String, List<String>> types = null;
        if (config.inputTypes().beyondPlainText() || !config.inputTypes().types().contains(DocumentType.TEXT)) {
            types = new TreeMap<>();
            for (final DocumentType type : config.inputTypes().types()) {
                types.put(type.json(), config.extensions(type));
            }
        }
        return new RunSettings(Formats.FORMAT, config.runId(), config.output().mode().json(),
                config.plainText().charsetValue().name(), config.plainText().extensions(), policySha256, types, key);
    }

    /** The entries for one file: the whole file, or segments of about {@code segmentBytes}. */
    static List<ShardEntry> segments(final String id, final ObjectInfo object, final String extension,
                                     final long segmentBytes) {
        final long size = object.size();
        final int count = size <= segmentBytes ? 1 : (int) Math.min(Integer.MAX_VALUE, (size + segmentBytes - 1) / segmentBytes);
        final List<ShardEntry> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final long start = count == 1 ? 0 : i * segmentBytes;
            final long end = count == 1 ? size : Math.min(size, (i + 1) * segmentBytes);
            result.add(new ShardEntry(id, object.key(), object.etag(), size, extension, i, count, start, end));
        }
        return result;
    }

    private static void skip(final List<SkippedObject> skipped, final Map<String, Long> byReason,
                             final ObjectInfo object, final String extension, final SkipReason reason) {
        skipped.add(new SkippedObject(object.key(), object.size(), extension, reason.name()));
        byReason.merge(reason.name(), 1L, Long::sum);
    }

    private void writeSkipped(final List<SkippedObject> skipped) {
        int file = 0;
        for (int from = 0; from < skipped.size(); from += SKIPPED_PER_FILE) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (final SkippedObject s : skipped.subList(from, Math.min(skipped.size(), from + SKIPPED_PER_FILE))) {
                out.writeBytes(Json.write(s));
                out.write('\n');
            }
            store.put(layout.bucket(), layout.skipped(file++), out.toByteArray(), "application/x-ndjson");
        }
        for (final ObjectInfo old : store.list(layout.bucket(), layout.skippedPrefix())) {
            final String name = old.key().substring(layout.skippedPrefix().length()).replace(".jsonl", "");
            if (Integer.parseInt(name) >= file) {
                store.delete(layout.bucket(), old.key());
            }
        }
    }

    /** The run's secret for document IDs: created once, then read back, so IDs are the same on a rerun. */
    private byte[] idSecret() {
        final byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try {
            store.putIfAbsent(layout.bucket(), layout.idKey(), secret, "application/octet-stream");
            return secret;
        } catch (final PreconditionFailedException e) {
            return store.get(layout.bucket(), layout.idKey()).orElseThrow().data();
        }
    }

    /** Writes an object once; a rerun finds it and checks it is the same. */
    private void putOnce(final String key, final byte[] data, final String contentType, final String conflict) {
        try {
            store.putIfAbsent(layout.bucket(), key, data, contentType);
        } catch (final PreconditionFailedException e) {
            final byte[] current = store.get(layout.bucket(), key).orElseThrow().data();
            if (!java.util.Arrays.equals(current, data)) {
                throw new ConfigException("Run " + config.runId() + " was started with "
                        + (conflict == null ? "different settings" : conflict) + ". Use a new run_id.");
            }
        }
    }

}
