/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    private static final byte[] POLICY = Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8);

    private static List<Shard> shards(final InMemoryObjectStore store, final RunLayout layout) {
        final List<Shard> shards = new ArrayList<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.shardsPrefix())) {
            shards.add(Json.read(store.get(layout.bucket(), o.key()).orElseThrow().data(), Shard.class));
        }
        return shards;
    }

    @Test
    void classifiesSegmentsAndShards() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        Fixtures.put(store, "in/a.txt", "a".repeat(40));
        Fixtures.put(store, "in/b.TXT", "b".repeat(40));
        Fixtures.put(store, "in/notes.md", "c".repeat(30));
        Fixtures.put(store, "in/big.log", "line\n".repeat(50));
        Fixtures.put(store, "in/report.pdf", "%PDF");
        Fixtures.put(store, "in/noext", "x");
        Fixtures.put(store, "in/empty.txt", "");
        Fixtures.put(store, "in/huge.txt", "h".repeat(2000));
        Fixtures.put(store, "in/folder/", "");
        Fixtures.put(store, "elsewhere/c.txt", "outside the prefix");
        final RunConfig config = Fixtures.config("""
                inventory: {shard_target_bytes: 100, segment_bytes: 100, max_file_bytes: 1000, max_line_bytes: 20}
                """);
        final InventorySummary summary = new Inventory(store, config, TestClock.at("2026-11-25T00:00:00Z")).run(POLICY);

        assertEquals(4, summary.documents());
        assertEquals(40 + 40 + 30 + 250, summary.bytes());
        // big.log (250 bytes) is three segments of 100, 100, and 50.
        assertEquals(6, summary.entries());
        assertEquals(java.util.Map.of("UNSUPPORTED_TYPE", 2L, "EMPTY_FILE", 1L, "TOO_LARGE", 1L,
                "DIRECTORY_MARKER", 1L), summary.skippedByReason());
        assertTrue(summary.byExtension().get("txt").included());
        assertEquals(4, summary.byExtension().get("txt").objects());
        assertFalse(summary.byExtension().get("pdf").included());
        assertEquals(1, summary.byExtension().get(Classifier.NONE).objects());

        final RunLayout layout = new RunLayout(config);
        final List<Shard> shards = shards(store, layout);
        assertEquals(summary.shards(), shards.size());
        final List<ShardEntry> all = shards.stream().flatMap(s -> s.entries().stream()).toList();
        assertEquals(6, all.size());
        for (final Shard s : shards) {
            assertTrue(s.bytes() <= 100 || s.entries().size() == 1, "shard " + s.shard() + " is over target");
        }
        final List<ShardEntry> big = all.stream().filter(e -> e.key().equals("in/big.log")).toList();
        assertEquals(3, big.size());
        assertEquals(List.of(0L, 100L, 200L), big.stream().map(ShardEntry::start).toList());
        assertEquals(List.of(100L, 200L, 250L), big.stream().map(ShardEntry::end).toList());
        assertEquals(1, big.stream().map(ShardEntry::documentId).distinct().count());
        assertEquals(32, big.getFirst().documentId().length());
        assertEquals("txt", all.stream().filter(e -> e.key().equals("in/b.TXT")).findFirst().orElseThrow().extension());

        // Skipped objects are listed, with keys, only under the run's prefix.
        final String skipped = new String(store.get(layout.bucket(), layout.skipped(0)).orElseThrow().data(),
                StandardCharsets.UTF_8);
        assertEquals(5, skipped.lines().count());
        assertTrue(skipped.contains("\"reason\":\"TOO_LARGE\""));
        assertTrue(store.get(layout.bucket(), layout.policy()).isPresent());
        assertTrue(store.get(layout.bucket(), layout.settings()).isPresent());
    }

    @Test
    void isIdempotentAndKeepsIds() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        Fixtures.put(store, "in/a.txt", "hello");
        final RunConfig config = Fixtures.config("");
        final RunLayout layout = new RunLayout(config);
        final Inventory inventory = new Inventory(store, config, TestClock.at("2026-11-25T00:00:00Z"));
        inventory.run(POLICY);
        final String id = shards(store, layout).getFirst().entries().getFirst().documentId();

        // Simulate a crash before inventory.json: delete it and rerun; the IDs stay the same.
        store.delete(layout.bucket(), layout.inventory());
        inventory.run(POLICY);
        assertEquals(id, shards(store, layout).getFirst().entries().getFirst().documentId());

        // Once complete, a rerun changes nothing even if the input grew.
        Fixtures.put(store, "in/b.txt", "more");
        assertEquals(1, inventory.run(POLICY).documents());

        // A different policy for the same run is refused.
        store.delete(layout.bucket(), layout.inventory());
        assertThrows(ConfigException.class, () -> inventory.run("{\"identifiers\":{}}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void idsDifferAcrossRuns() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        Fixtures.put(store, "in/a.txt", "hello");
        final RunConfig one = Fixtures.config("");
        final RunConfig two = ai.philterd.batch.config.ConfigLoader.parse("""
                run_id: other-run
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: out/}
                """);
        new Inventory(store, one, TestClock.at("2026-11-25T00:00:00Z")).run(POLICY);
        new Inventory(store, two, TestClock.at("2026-11-25T00:00:00Z")).run(POLICY);
        final String a = shards(store, new RunLayout(one)).getFirst().entries().getFirst().documentId();
        final String b = shards(store, new RunLayout(two)).getFirst().entries().getFirst().documentId();
        assertNotEquals(a, b, "IDs are keyed per run, so they cannot be joined across runs");
    }

    @Test
    void shrinkingListingRemovesStaleShards() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        for (int i = 0; i < 5; i++) {
            Fixtures.put(store, "in/f" + i + ".txt", "x".repeat(100));
        }
        final RunConfig config = Fixtures.config("inventory: {shard_target_bytes: 100}\n");
        final RunLayout layout = new RunLayout(config);
        final Inventory inventory = new Inventory(store, config, TestClock.at("2026-11-25T00:00:00Z"));
        assertEquals(5, inventory.run(POLICY).shards());
        store.delete(layout.bucket(), layout.inventory());
        for (int i = 2; i < 5; i++) {
            store.delete(Fixtures.INPUT, "in/f" + i + ".txt");
        }
        assertEquals(2, inventory.run(POLICY).shards());
        assertEquals(2, store.list(layout.bucket(), layout.shardsPrefix()).size());
    }

    @Test
    void unsplittableCharsetKeepsFilesWhole() {
        assertTrue(Classifier.splittable(StandardCharsets.UTF_8));
        assertTrue(Classifier.splittable(StandardCharsets.ISO_8859_1));
        assertTrue(Classifier.splittable(Charset.forName("windows-1252")));
        assertFalse(Classifier.splittable(StandardCharsets.UTF_16));
        assertFalse(Classifier.splittable(StandardCharsets.UTF_16LE));

        final InMemoryObjectStore store = new InMemoryObjectStore();
        store.put(Fixtures.INPUT, "in/wide.txt", "line\n".repeat(100).getBytes(StandardCharsets.UTF_16LE), "text/plain");
        final RunConfig config = Fixtures.config("""
                plain_text: {charset: UTF-16LE}
                inventory: {segment_bytes: 100}
                """);
        assertEquals(1, new Inventory(store, config, TestClock.at("2026-11-25T00:00:00Z")).run(POLICY).entries());
    }

    @Test
    void extensions() {
        assertEquals("txt", Classifier.extension("a/b/c.TXT"));
        assertEquals(Classifier.NONE, Classifier.extension("a/b.d/c"));
        assertEquals(Classifier.NONE, Classifier.extension("a/.hidden"));
        assertEquals(Classifier.NONE, Classifier.extension("a/trailing."));
        assertEquals(Classifier.OTHER, Classifier.extension("a/name.Jane_Example_Smith"));
        assertEquals(Classifier.OTHER, Classifier.extension("a/name.averyveryverylongext"));
    }

    @Test
    void plannerRespectsTargetAndEntryLimit() {
        final List<ShardEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(new ShardEntry("id" + i, "k" + i, "\"e\"", 30, "txt", 0, 1, 0, 30));
        }
        assertEquals(4, ShardPlanner.plan(entries, 90, 100).size());
        assertEquals(5, ShardPlanner.plan(entries, 1000, 2).size());
        assertEquals(10, ShardPlanner.plan(entries, 1, 100).size());
        assertEquals(0, ShardPlanner.plan(List.of(), 1, 1).size());
    }

}
