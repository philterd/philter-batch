/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.finalize;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.ManifestEntry;
import ai.philterd.batch.run.Formats.ModelInfo;
import ai.philterd.batch.run.Formats.Report;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.worker.ReasonCode;
import ai.philterd.batch.worker.Worker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finishes a run once every shard has a done marker: deletes the parts no done marker names (from workers that
 * lost a shard or finished second), puts each redacted document in place, deletes the staging area, and writes the
 * manifest, the report, and {@code _SUCCESS}. Safe to run again after a crash.
 */
public final class Finalizer {

    private static final Logger LOGGER = LogManager.getLogger(Finalizer.class);

    /** Documents put in place at once. */
    static final int COMPOSE_THREADS = 8;

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final Clock clock;

    public Finalizer(final ObjectStore store, final RunConfig config, final Clock clock) {
        this.store = store;
        this.config = config;
        this.layout = new RunLayout(config);
        this.clock = clock;
    }

    /** A document entry of one done marker, with its shard entry. */
    private record Piece(ShardEntry entry, DocumentResult result) {
    }

    public int run() {
        if (store.get(layout.bucket(), layout.success()).isPresent()) {
            LOGGER.info("Run {} is already finalized.", config.runId());
            return ExitCodes.OK;
        }
        final RunSettings settings = Worker.checkSettings(store, config);
        final InventorySummary inventory = Json.read(store.get(layout.bucket(), layout.inventory()).orElseThrow().data(),
                InventorySummary.class);

        final Set<String> shardIds = new java.util.TreeSet<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.shardsPrefix())) {
            shardIds.add(RunLayout.shardOf(o.key()));
        }
        final Set<String> doneIds = new HashSet<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.donePrefix())) {
            doneIds.add(RunLayout.shardOf(o.key()));
        }
        final long missing = shardIds.stream().filter(id -> !doneIds.contains(id)).count();
        if (missing > 0) {
            LOGGER.warn("Run {} is not complete: {} of {} shards have no done marker.", config.runId(), missing,
                    shardIds.size());
            return ExitCodes.INCOMPLETE;
        }

        final Map<String, DoneMarker> done = new TreeMap<>();
        final Map<String, Shard> shards = new TreeMap<>();
        String phileasVersion = null;
        String pheyeOnnxVersion = null;
        List<ModelInfo> models = null;
        for (final String id : shardIds) {
            final DoneMarker marker = Json.read(store.get(layout.bucket(), layout.done(id)).orElseThrow().data(),
                    DoneMarker.class);
            FormatException.check("Done marker " + id, marker.format(), Formats.FORMAT);
            if (!settings.policySha256().equals(marker.policySha256())) {
                LOGGER.error("Shard {} was processed with a different policy than the run's.", id);
                return ExitCodes.ERROR;
            }
            if (phileasVersion == null) {
                phileasVersion = marker.phileasVersion();
                pheyeOnnxVersion = marker.pheyeOnnxVersion();
                models = marker.models() == null ? List.of() : marker.models();
            } else if (!phileasVersion.equals(marker.phileasVersion())
                    || !pheyeOnnxVersion.equals(marker.pheyeOnnxVersion())
                    || !models.equals(marker.models() == null ? List.of() : marker.models())) {
                LOGGER.error("Shard {} was processed with a different Phileas version or model than other shards.", id);
                return ExitCodes.ERROR;
            }
            done.put(id, marker);
            final Shard shard = Json.read(store.get(layout.bucket(), layout.shard(id)).orElseThrow().data(), Shard.class);
            shards.put(id, shard);
        }

        // Parts no done marker names: a worker that lost its shard, or one that finished second.
        final Set<String> namedParts = new HashSet<>();
        done.values().forEach(d -> namedParts.addAll(d.parts()));
        long partsDeleted = 0;
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            if (!namedParts.contains(part.key())) {
                store.delete(layout.bucket(), part.key());
                partsDeleted++;
            }
        }

        // Each document's pieces across shards, for document output.
        final Map<String, List<Piece>> byDocument = new TreeMap<>();
        for (final Map.Entry<String, DoneMarker> e : done.entrySet()) {
            final List<ShardEntry> entries = shards.get(e.getKey()).entries();
            for (final DocumentResult r : e.getValue().results()) {
                byDocument.computeIfAbsent(r.documentId(), k -> new ArrayList<>()).add(new Piece(entries.get(r.entry()), r));
            }
        }
        final Map<String, String> documentKeys = new HashMap<>();
        long withheld = 0;
        if (config.output().mode().document()) {
            final Set<String> existing = new HashSet<>();
            store.list(layout.bucket(), layout.documentsPrefix()).forEach(o -> existing.add(o.key()));
            final Set<String> staging = new HashSet<>();
            store.list(layout.bucket(), layout.stagingPrefix()).forEach(o -> staging.add(o.key()));
            final List<Runnable> composes = new ArrayList<>();
            for (final Map.Entry<String, List<Piece>> e : byDocument.entrySet()) {
                final List<Piece> pieces = new ArrayList<>(e.getValue());
                pieces.sort(Comparator.comparingInt(p -> p.result().segment()));
                if (pieces.stream().anyMatch(p -> DocumentResult.QUARANTINED.equals(p.result().status()))) {
                    if (pieces.stream().anyMatch(p -> DocumentResult.REDACTED.equals(p.result().status()))) {
                        withheld++;
                    }
                    continue;
                }
                final List<String> sources = pieces.stream().map(p -> p.result().staged())
                        .filter(java.util.Objects::nonNull).toList();
                if (sources.isEmpty()) {
                    continue;
                }
                final String target = layout.document(e.getKey(), pieces.getFirst().entry().extension());
                documentKeys.put(e.getKey(), target);
                if (existing.contains(target) && sources.stream().noneMatch(staging::contains)) {
                    continue;
                }
                if (!staging.containsAll(sources)) {
                    LOGGER.error("Document {} has staged pieces missing; the run's output is inconsistent.", e.getKey());
                    return ExitCodes.ERROR;
                }
                composes.add(() -> store.compose(layout.bucket(), target, sources, "text/plain; charset="
                        + config.plainText().charsetValue().name()));
            }
            if (!runAll(composes)) {
                return ExitCodes.ERROR;
            }
        }
        long stagedDeleted = 0;
        for (final ObjectInfo staged : store.list(layout.bucket(), layout.stagingPrefix())) {
            store.delete(layout.bucket(), staged.key());
            stagedDeleted++;
        }

        // The manifest: the only place an output ID meets its input key.
        final Map<String, Long> byStatus = new TreeMap<>();
        final Map<String, Long> byReason = new TreeMap<>();
        final Map<String, Long> pii = new TreeMap<>();
        long records = 0;
        long entries = 0;
        long leftoverChecked = 0;
        for (final Map.Entry<String, DoneMarker> e : done.entrySet()) {
            final List<ShardEntry> shardEntries = shards.get(e.getKey()).entries();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (final DocumentResult r : e.getValue().results()) {
                final ShardEntry entry = shardEntries.get(r.entry());
                entries++;
                byStatus.merge(r.status(), 1L, Long::sum);
                if (r.reason() != null) {
                    byReason.merge(r.reason(), 1L, Long::sum);
                }
                if (r.piiCounts() != null) {
                    r.piiCounts().forEach((type, n) -> pii.merge(type, (long) n, Long::sum));
                }
                if (r.record()) {
                    records++;
                }
                if (Boolean.TRUE.equals(r.leftoverChecked())) {
                    leftoverChecked++;
                }
                String reason = r.reason();
                if (reason == null && config.output().mode().document() && DocumentResult.REDACTED.equals(r.status())
                        && !documentKeys.containsKey(r.documentId())) {
                    reason = ReasonCode.SEGMENT_QUARANTINED.name();
                }
                out.writeBytes(Json.write(new ManifestEntry(r.documentId(), entry.key(), entry.etag(), entry.size(),
                        r.segment(), r.segments(), r.status(), reason, documentKeys.get(r.documentId()))));
                out.write('\n');
            }
            store.put(layout.bucket(), layout.manifestDocuments(e.getKey()), out.toByteArray(), "application/x-ndjson");
        }
        final ByteArrayOutputStream skipped = new ByteArrayOutputStream();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.skippedPrefix())) {
            skipped.writeBytes(store.get(layout.bucket(), o.key()).orElseThrow().data());
        }
        store.put(layout.bucket(), layout.manifestSkipped(), skipped.toByteArray(), "application/x-ndjson");

        final Report report = new Report(Formats.FORMAT, config.runId(), clock.instant(), settings.outputMode(),
                shardIds.size(), inventory.documents(), entries, byStatus, byReason, inventory.skippedByReason(), pii,
                leftoverChecked, records, documentKeys.size(), withheld, partsDeleted, stagedDeleted);
        store.put(layout.bucket(), layout.report(), Json.write(report), "application/json");
        final Success success = new Success(Formats.FORMAT, config.runId(), clock.instant(), settings.policySha256(),
                phileasVersion, pheyeOnnxVersion, models, System.getenv("PHILTER_BATCH_IMAGE_DIGEST"),
                settings.outputMode(), report);
        store.put(layout.bucket(), layout.success(), Json.write(success), "application/json");
        LOGGER.info("Finalized run {}: {} entries {}, {} records, {} documents, quarantined {}.", config.runId(),
                entries, byStatus, records, documentKeys.size(), byReason);
        return ExitCodes.OK;
    }

    private boolean runAll(final List<Runnable> tasks) {
        if (tasks.isEmpty()) {
            return true;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(COMPOSE_THREADS, tasks.size()));
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (final Runnable task : tasks) {
                futures.add(pool.submit(task));
            }
            for (final Future<?> f : futures) {
                f.get();
            }
            return true;
        } catch (final ExecutionException e) {
            LOGGER.error("Putting a document in place failed: {}", e.getCause().getClass().getName());
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

}
