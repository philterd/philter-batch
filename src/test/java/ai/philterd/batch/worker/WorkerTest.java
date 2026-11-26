/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.coordination.ClaimResult;
import ai.philterd.batch.coordination.Coordinator;
import ai.philterd.batch.coordination.HeldShard;
import ai.philterd.batch.coordination.StoreClock;
import ai.philterd.batch.finalize.Finalizer;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.ManifestEntry;
import ai.philterd.batch.run.Formats.Progress;
import ai.philterd.batch.run.Formats.Report;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Formats.TextRecord;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStoreException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerTest {

    private static final byte[] POLICY = Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8);

    private final TestClock clock = TestClock.at("2026-11-25T12:00:00Z");
    private final InMemoryObjectStore store = new InMemoryObjectStore(clock);
    private final Fixtures.FakeRedactor redactor = new Fixtures.FakeRedactor();

    private Worker worker(final RunConfig config, final StopSignal stop) {
        return new Worker(store, config, redactor, List.of(), clock, stop, "proc");
    }

    private String text(final String key) {
        return new String(store.get(Fixtures.OUTPUT, key).orElseThrow().data(), StandardCharsets.UTF_8);
    }

    private List<TextRecord> records(final RunLayout layout) {
        final List<TextRecord> records = new ArrayList<>();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            for (final String line : text(part.key()).split("\n")) {
                records.add(Json.MAPPER.convertValue(Json.read(line.getBytes(StandardCharsets.UTF_8), Map.class),
                        TextRecord.class));
            }
        }
        return records;
    }

    private List<ManifestEntry> manifest(final RunLayout layout) {
        final List<ManifestEntry> entries = new ArrayList<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.manifestPrefix() + "documents-")) {
            for (final String line : text(o.key()).split("\n")) {
                entries.add(Json.read(line.getBytes(StandardCharsets.UTF_8), ManifestEntry.class));
            }
        }
        return entries;
    }

    @Test
    void runsAWholeRunInTextMode() {
        Fixtures.put(store, "in/Jane Example/letter.txt", "Dear friend, my code is secret-alpha and secret-beta.\n");
        Fixtures.put(store, "in/notes.md", "Nothing sensitive here.\n");
        Fixtures.put(store, "in/scan.pdf", "%PDF-1.7 not handled yet");
        for (int i = 0; i < 30; i++) {
            Fixtures.put(store, "in/bulk/" + i + ".txt", "record " + i + " secret-n" + i + "\n");
        }
        final RunConfig config = Fixtures.config("inventory: {shard_target_bytes: 100}\nworker: {threads: 4, onnx_threads: 1}\n");
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());

        final RunLayout layout = new RunLayout(config);
        final List<TextRecord> records = records(layout);
        assertEquals(32, records.size());
        final String allOutput = store.list(layout.bucket(), layout.partsPrefix()).stream().map(o -> text(o.key()))
                .collect(Collectors.joining());
        assertFalse(allOutput.contains("secret-"), "no unredacted value reaches the output");
        assertFalse(allOutput.contains("Jane Example"), "no key or file name reaches the output");
        assertFalse(allOutput.contains("letter"), "no key or file name reaches the output");
        assertFalse(allOutput.contains("in/"), "no key reaches the output");
        final TextRecord letter = records.stream().filter(r -> r.text().startsWith("Dear")).findFirst().orElseThrow();
        assertEquals("Dear friend, my code is [REDACTED] and [REDACTED].\n", letter.text());
        assertEquals(Map.of("secret", 2), letter.piiCounts());
        assertEquals(1, letter.schemaVersion());
        assertEquals("text", letter.type());
        assertEquals("test-run", letter.runId());
        assertTrue(letter.policy().startsWith("sha256:"));

        // The manifest maps the opaque ID back to the key; nothing else does.
        final ManifestEntry mapped = manifest(layout).stream().filter(m -> m.documentId().equals(letter.documentId()))
                .findFirst().orElseThrow();
        assertEquals("in/Jane Example/letter.txt", mapped.key());
        assertEquals(DocumentResult.REDACTED, mapped.status());

        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(ai.philterd.batch.run.Versions.phileas(), success.phileasVersion());
        // From Phileas 4.6.0, phileas-pheye-onnx has Phileas's version.
        assertEquals(success.phileasVersion(), success.pheyeOnnxVersion());
        assertEquals(32, success.counts().records());
        assertEquals(Map.of("UNSUPPORTED_TYPE", 1L), success.counts().skippedByReason());
        assertEquals(Long.valueOf(32), success.counts().piiCounts().get("secret"));
        assertTrue(text(layout.manifestSkipped()).contains("in/scan.pdf"));
        assertEquals(32, redactor.totalCalls(), "each document is redacted once");
        // A second finalize does nothing.
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
    }

    @Test
    void failsClosed() {
        Fixtures.put(store, "in/good.txt", "fine secret-ok\n");
        store.put(Fixtures.INPUT, "in/binary.txt", new byte[] {(byte) 0xC3, (byte) 0x28, 0x41}, "text/plain");
        Fixtures.put(store, "in/leftover.txt", "still has LEFTOVER inside\n");
        Fixtures.put(store, "in/explode.txt", "EXPLODE\n");
        Fixtures.put(store, "in/changed.txt", "original\n");
        Fixtures.put(store, "in/gone.txt", "will be deleted\n");
        final RunConfig config = Fixtures.config("output: {bucket: output-bucket, prefix: out/, mode: both}\n");
        new Inventory(store, config, clock).run(POLICY);
        Fixtures.put(store, "in/changed.txt", "edited after inventory\n");
        store.delete(Fixtures.INPUT, "in/gone.txt");

        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
        final RunLayout layout = new RunLayout(config);
        final Map<String, String> reasons = manifest(layout).stream().collect(Collectors.toMap(ManifestEntry::key,
                m -> m.reason() == null ? m.status() : m.reason()));
        assertEquals(Map.of("in/good.txt", "redacted", "in/binary.txt", "UNDECODABLE",
                "in/leftover.txt", "LEFTOVER_FOUND", "in/explode.txt", "REDACTION_FAILED",
                "in/changed.txt", "CHANGED_SINCE_INVENTORY",
                "in/gone.txt", "INPUT_MISSING"), reasons);

        final List<TextRecord> records = records(layout);
        assertEquals(1, records.size());
        assertEquals("fine [REDACTED]\n", records.getFirst().text());
        final List<ObjectInfo> documents = store.list(layout.bucket(), layout.documentsPrefix());
        assertEquals(1, documents.size());
        assertEquals("fine [REDACTED]\n", text(documents.getFirst().key()));
        assertTrue(documents.getFirst().key().endsWith(records.getFirst().documentId() + ".txt"));
        assertTrue(store.list(layout.bucket(), layout.stagingPrefix()).isEmpty());

        final Report report = Json.read(store.get(layout.bucket(), layout.report()).orElseThrow().data(), Report.class);
        assertEquals(5, report.quarantinedByReason().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(2, report.leftoverChecked());
        // The leftover finding is recorded by type in the done marker, never by value.
        final String done = store.list(layout.bucket(), layout.donePrefix()).stream().map(o -> text(o.key()))
                .collect(Collectors.joining());
        assertTrue(done.contains("\"leftover_counts\":{\"email-address\":1}"));
        assertFalse(done.contains("LEFTOVER inside"));
    }

    @Test
    void timesOutSlowRedactions() {
        Fixtures.put(store, "in/slow.txt", "slow\n");
        redactor.delayMillis = 3_000;
        final RunConfig config = Fixtures.config("worker: {threads: 1, onnx_threads: 1, file_timeout_seconds: 1}\n");
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
        assertEquals("TIMEOUT", manifest(new RunLayout(config)).getFirst().reason());
    }

    @Test
    void encodingFailuresAreQuarantinedInDocumentMode() {
        store.put(Fixtures.INPUT, "in/latin.txt", "café OMEGA\n".getBytes(StandardCharsets.ISO_8859_1), "text/plain");
        store.put(Fixtures.INPUT, "in/ok.txt", "café secret-x\n".getBytes(StandardCharsets.ISO_8859_1), "text/plain");
        final RunConfig config = Fixtures.config("""
                output: {bucket: output-bucket, prefix: out/, mode: document}
                plain_text: {charset: ISO-8859-1}
                """);
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
        final RunLayout layout = new RunLayout(config);
        final Map<String, ManifestEntry> byKey = manifest(layout).stream()
                .collect(Collectors.toMap(ManifestEntry::key, m -> m));
        // The redactor put an Omega in the text, which ISO-8859-1 cannot hold.
        assertEquals("ENCODING_FAILED", byKey.get("in/latin.txt").reason());
        assertNull(byKey.get("in/latin.txt").document());
        final List<ObjectInfo> documents = store.list(Fixtures.OUTPUT, layout.documentsPrefix());
        assertEquals(1, documents.size());
        assertEquals("café [REDACTED]\n", new String(store.get(Fixtures.OUTPUT, documents.getFirst().key())
                .orElseThrow().data(), StandardCharsets.ISO_8859_1));
        assertTrue(store.list(Fixtures.OUTPUT, layout.partsPrefix()).isEmpty(), "document mode writes no JSONL");
    }

    @Test
    void segmentedFilesInDocumentModeAreReassembled() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("line ").append(i).append(" secret-v").append(i).append('\n');
        }
        Fixtures.put(store, "in/big.log", sb.toString());
        Fixtures.put(store, "in/bad.log", "ok line\n".repeat(30) + "EXPLODE here\n" + "ok line\n".repeat(30));
        final RunConfig config = Fixtures.config("""
                output: {bucket: output-bucket, prefix: out/, mode: both}
                inventory: {shard_target_bytes: 400, segment_bytes: 200, max_line_bytes: 100}
                worker: {threads: 3, onnx_threads: 1}
                """);
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
        final RunLayout layout = new RunLayout(config);

        final String expected = sb.toString().replaceAll("secret-v\\d+", "[REDACTED]");
        final List<ManifestEntry> manifest = manifest(layout);
        final String bigId = manifest.stream().filter(m -> m.key().equals("in/big.log")).findFirst().orElseThrow()
                .documentId();
        assertEquals(expected, text(layout.document(bigId, "log")));
        // Text mode: one record per segment, in order they reassemble the file.
        final String fromRecords = records(layout).stream().filter(r -> r.documentId().equals(bigId))
                .sorted(java.util.Comparator.comparingInt(TextRecord::segment)).map(TextRecord::text)
                .collect(Collectors.joining());
        assertEquals(expected, fromRecords);

        // One quarantined segment withholds the whole document, and its other segments are marked.
        final List<ManifestEntry> bad = manifest.stream().filter(m -> m.key().equals("in/bad.log")).toList();
        assertTrue(bad.stream().anyMatch(m -> "REDACTION_FAILED".equals(m.reason())));
        assertTrue(bad.stream().anyMatch(m -> "SEGMENT_QUARANTINED".equals(m.reason())));
        assertTrue(bad.stream().allMatch(m -> m.document() == null));
        assertTrue(store.get(layout.bucket(), layout.document(bad.getFirst().documentId(), "log")).isEmpty());
        final Report report = Json.read(store.get(layout.bucket(), layout.report()).orElseThrow().data(), Report.class);
        assertEquals(1, report.documentsWithheld());
    }

    // --- Crash and takeover scenarios, driving the shard processor directly. ---

    private final class Harness {
        final RunConfig config;
        final RunLayout layout;

        Harness(final RunConfig config, final int files) {
            this.config = config;
            this.layout = new RunLayout(config);
            for (int i = 0; i < files; i++) {
                Fixtures.put(store, "in/f" + String.format("%02d", i) + ".txt", "file " + i + " secret-s" + i + "\n");
            }
            new Inventory(store, config, clock).run(POLICY);
        }

        Coordinator coordinator(final long seed) {
            return new Coordinator(store, layout, config.lease(), new StoreClock(store, layout.bucket(), clock,
                    Duration.ofSeconds(30)), new Random(seed));
        }

        ShardProcessor processor(final String worker, final Coordinator coordinator, final EntryProcessor entries,
                                 final StopSignal stop) {
            return new ShardProcessor(store, config, layout, coordinator, entries, stop, worker, ai.philterd.batch.run.Hashes.sha256(POLICY),
                    List.of(), clock);
        }

        DocumentProcessor documents(final String worker) {
            final ExecutorService pool = Executors.newCachedThreadPool();
            return new DocumentProcessor(store, config, layout, redactor, ai.philterd.batch.run.Hashes.sha256(POLICY), worker, pool);
        }

        Progress progress() {
            return Json.read(store.get(layout.bucket(), layout.progress("000000")).orElseThrow().data(), Progress.class);
        }
    }

    /** Simulates the process dying while working on one entry. */
    private static final class Crash extends RuntimeException {
    }

    @Test
    void crashBetweenUploadingAPartAndWritingTheDoneMarker() {
        final Harness h = new Harness(Fixtures.config(""), 3);
        final Coordinator a = h.coordinator(1);
        final HeldShard held = assertInstanceOf(ClaimResult.Claimed.class, a.claim("a")).shard();
        store.setFault((op, key) -> key.contains("/done/"));
        assertThrows(ObjectStoreException.class, () -> h.processor("a", a, h.documents("a"), new StopSignal()).process(held));
        store.setFault(null);
        assertEquals(3, h.progress().cursor(), "the part and final progress were written");
        assertEquals(1, h.progress().parts().size());
        assertEquals(3, redactor.totalCalls());

        // The lease expires; another worker finds the cursor at the end and only writes the done marker.
        clock.advance(Duration.ofSeconds(70));
        final Coordinator b = h.coordinator(2);
        final HeldShard taken = assertInstanceOf(ClaimResult.Claimed.class, b.claim("b")).shard();
        assertEquals(ShardProcessor.Result.DONE, h.processor("b", b, h.documents("b"), new StopSignal()).process(taken));
        assertEquals(3, redactor.totalCalls(), "nothing was redacted twice");
        final DoneMarker done = Json.read(store.get(h.layout.bucket(), h.layout.done("000000")).orElseThrow().data(),
                DoneMarker.class);
        assertEquals("b", done.holder());
        assertEquals(h.progress().parts(), done.parts());
        assertEquals(ExitCodes.OK, new Finalizer(store, h.config, clock).run());
        assertEquals(3, records(h.layout).size());
    }

    @Test
    void crashBetweenUploadingAPartAndSavingProgressLeavesAnOrphanThatFinalizeDeletes() {
        final Harness h = new Harness(Fixtures.config(""), 3);
        final Coordinator a = h.coordinator(1);
        final HeldShard held = assertInstanceOf(ClaimResult.Claimed.class, a.claim("a")).shard();
        // The part upload succeeds; the progress write after it fails, as if the pod died between them.
        store.setFault((op, key) -> op.equals("putIfMatch") && key.contains("/progress/"));
        assertThrows(ObjectStoreException.class, () -> h.processor("a", a, h.documents("a"), new StopSignal()).process(held));
        store.setFault(null);
        assertEquals(1, store.list(h.layout.bucket(), h.layout.partsPrefix()).size());

        clock.advance(Duration.ofSeconds(70));
        final Coordinator b = h.coordinator(2);
        final HeldShard taken = assertInstanceOf(ClaimResult.Claimed.class, b.claim("b")).shard();
        assertEquals(1, taken.claim().stalledAttempts(), "the cursor did not move");
        h.processor("b", b, h.documents("b"), new StopSignal()).process(taken);
        // B saves progress after every entry (careful mode, since the cursor had not moved): one part each.
        assertEquals(4, store.list(h.layout.bucket(), h.layout.partsPrefix()).size());

        assertEquals(ExitCodes.OK, new Finalizer(store, h.config, clock).run());
        final List<ObjectInfo> parts = store.list(h.layout.bucket(), h.layout.partsPrefix());
        assertEquals(3, parts.size(), "the orphaned part was deleted");
        assertTrue(parts.stream().allMatch(p -> p.key().contains("-b-")));
        assertEquals(3, records(h.layout).size(), "each record appears once");
        final Report report = Json.read(store.get(h.layout.bucket(), h.layout.report()).orElseThrow().data(), Report.class);
        assertEquals(1, report.partsDeleted());
    }

    @Test
    void aPoisonFileIsQuarantinedAfterRepeatedDeaths() {
        final Harness h = new Harness(Fixtures.config("lease: {initial_seconds: 60, renew_seconds: 10, "
                + "max_seconds: 60, poison_attempts: 2}\n"), 5);
        final String poisonKey = "in/f02.txt";
        int deaths = 0;
        for (int round = 0; round < 10; round++) {
            final Coordinator c = h.coordinator(round);
            final ClaimResult result = c.claim("w" + round);
            if (result instanceof ClaimResult.NoWorkLeft) {
                break;
            }
            final HeldShard held = assertInstanceOf(ClaimResult.Claimed.class, result).shard();
            final DocumentProcessor real = h.documents("w" + round);
            final EntryProcessor dying = (index, entry) -> {
                if (entry.key().equals(poisonKey)) {
                    throw new Crash();
                }
                return real.process(index, entry);
            };
            try {
                h.processor("w" + round, c, dying, new StopSignal()).process(held);
            } catch (final Crash e) {
                deaths++;
                clock.advance(Duration.ofSeconds(70));
            }
        }
        assertTrue(deaths >= 2 && deaths <= 4, "deaths: " + deaths);
        assertEquals(ExitCodes.OK, new Finalizer(store, h.config, clock).run());
        final Map<String, String> status = manifest(h.layout).stream().collect(Collectors.toMap(ManifestEntry::key,
                m -> m.reason() == null ? m.status() : m.reason()));
        assertEquals("POISON", status.get(poisonKey));
        assertEquals(4, status.values().stream().filter("redacted"::equals).count());
        assertEquals(4, records(h.layout).size());
    }

    @Test
    void sigtermFinishesTheCurrentFileSavesTheCursorAndReleases() {
        final Harness h = new Harness(Fixtures.config(""), 5);
        final Coordinator a = h.coordinator(1);
        final HeldShard held = assertInstanceOf(ClaimResult.Claimed.class, a.claim("a")).shard();
        final StopSignal stop = new StopSignal();
        final DocumentProcessor real = h.documents("a");
        final EntryProcessor signalled = (index, entry) -> {
            if (index == 1) {
                stop.stop();
            }
            return real.process(index, entry);
        };
        assertEquals(ShardProcessor.Result.STOPPED, h.processor("a", a, signalled, stop).process(held));
        assertEquals(2, h.progress().cursor(), "the file in hand was finished");
        assertEquals(2, redactor.totalCalls());

        // Released, so another worker takes it at once, from the cursor, without a doubled lease.
        final Coordinator b = h.coordinator(2);
        final HeldShard taken = assertInstanceOf(ClaimResult.Claimed.class, b.claim("b")).shard();
        assertEquals(1, taken.claim().attempt());
        assertEquals(ShardProcessor.Result.DONE, h.processor("b", b, h.documents("b"), new StopSignal()).process(taken));
        assertEquals(5, redactor.totalCalls(), "no file was redacted twice");
        assertEquals(ExitCodes.OK, new Finalizer(store, h.config, clock).run());
        assertEquals(5, records(h.layout).size());
    }

    @Test
    void workerProcessExitCodes() {
        final RunConfig config = Fixtures.config("worker: {threads: 1, onnx_threads: 1}\n");
        assertThrows(ConfigException.class, () -> worker(config, new StopSignal()).run(false), "no inventory yet");
        Fixtures.put(store, "in/a.txt", "x secret-a\n");
        Fixtures.put(store, "in/b.txt", "y secret-b\n");
        new Inventory(store, Fixtures.config("inventory: {shard_target_bytes: 1}\n"), clock).run(POLICY);

        // Another worker holds every shard: exit 4, not 0, so the pod is not restarted in a loop.
        final RunLayout layout = new RunLayout(config);
        final Coordinator other = new Coordinator(store, layout, config.lease(),
                new StoreClock(store, layout.bucket(), clock, Duration.ofSeconds(30)), new Random(1));
        other.claim("other");
        other.claim("other");
        assertEquals(ExitCodes.ALL_LEASED, worker(config, new StopSignal()).run(false));

        // Stopped before starting: exit 3.
        final StopSignal stopped = new StopSignal();
        stopped.stop();
        assertEquals(ExitCodes.STOPPED, worker(config, stopped).run(false));

        // Once the other worker's leases expire, the shards are taken over and finished: exit 0.
        clock.advance(Duration.ofSeconds(70));
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
        assertEquals(ExitCodes.OK, worker(config, new StopSignal()).run(false));
    }

    @Test
    void aWorkerWithDifferentSettingsIsRefused() {
        Fixtures.put(store, "in/a.txt", "x\n");
        new Inventory(store, Fixtures.config(""), clock).run(POLICY);
        final RunConfig other = Fixtures.config("output: {bucket: output-bucket, prefix: out/, mode: document}\n");
        assertThrows(ConfigException.class, () -> worker(other, new StopSignal()).run(false));
    }

    @Test
    void finalizeWaitsForEveryShard() {
        Fixtures.put(store, "in/a.txt", "x\n");
        Fixtures.put(store, "in/b.txt", "y\n");
        final RunConfig config = Fixtures.config("inventory: {shard_target_bytes: 1}\n");
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.INCOMPLETE, new Finalizer(store, config, clock).run());
        assertNull(store.get(Fixtures.OUTPUT, new RunLayout(config).success()).orElse(null));
    }

    @Test
    void describeHidesForeignMessages() {
        assertEquals("java.lang.IllegalStateException", Worker.describe(new IllegalStateException("text: Jane")));
        assertEquals("ai.philterd.batch.store.ObjectStoreException: GetObject failed: 503",
                Worker.describe(new ObjectStoreException("GetObject failed: 503", null)));
        assertTrue(Worker.defaultProcessId().matches("[A-Za-z0-9-]+"));
        ConfigLoader.parse("run_id: x\ninput: {bucket: a}\noutput: {bucket: b}\n");
    }

}
