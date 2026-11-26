/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleSelectorTest {

    private static ShardEntry entry(final int i, final String extension, final long bytes) {
        return new ShardEntry("doc" + i, "in/" + i + "." + extension, "\"e\"", bytes, extension, 0, 1, 0, bytes);
    }

    /** 3 extensions by 4 size bands, 100 entries each, in listing order (grouped, as a real listing often is). */
    private static List<ShardEntry> corpus() {
        final List<ShardEntry> entries = new ArrayList<>();
        int i = 0;
        for (final String ext : List.of("log", "md", "txt")) {
            for (final long size : List.of(100L, 5_000L, 200_000L, 3_000_000L)) {
                for (int n = 0; n < 100; n++) {
                    entries.add(entry(i++, ext, size + n));
                }
            }
        }
        return entries;
    }

    @Test
    void sizeBandsGrowBySixteen() {
        assertEquals(0, SampleSelector.sizeBand(1));
        assertEquals(0, SampleSelector.sizeBand(15));
        assertEquals(1, SampleSelector.sizeBand(16));
        assertEquals(1, SampleSelector.sizeBand(255));
        assertEquals(2, SampleSelector.sizeBand(256));
        assertEquals(5, SampleSelector.sizeBand(1L << 20));
    }

    @Test
    void spreadsTheSampleAcrossTypesAndSizes() {
        final List<ShardEntry> entries = corpus();
        final List<Integer> chosen = SampleSelector.select(entries, 24, 42);
        assertEquals(24, chosen.size());
        // 12 groups and 24 places: every group gets exactly two.
        final java.util.Map<String, Integer> perGroup = new java.util.TreeMap<>();
        for (final int i : chosen) {
            final ShardEntry e = entries.get(i);
            perGroup.merge(e.extension() + "/" + SampleSelector.sizeBand(e.size()), 1, Integer::sum);
        }
        assertEquals(12, perGroup.size(), perGroup.toString());
        assertTrue(perGroup.values().stream().allMatch(n -> n == 2), perGroup.toString());

        // Fewer places than groups: no group gets two before every group has one.
        final List<Integer> few = SampleSelector.select(entries, 7, 42);
        final Set<String> groups = new HashSet<>();
        for (final int i : few) {
            final ShardEntry e = entries.get(i);
            assertTrue(groups.add(e.extension() + "/" + SampleSelector.sizeBand(e.size())));
        }
        assertEquals(7, groups.size());
    }

    @Test
    void theSameSeedChoosesTheSameSample() {
        final List<ShardEntry> entries = corpus();
        assertEquals(SampleSelector.select(entries, 50, 7), SampleSelector.select(entries, 50, 7));
        assertNotEquals(SampleSelector.select(entries, 50, 7), SampleSelector.select(entries, 50, 8));
        assertEquals(SampleSelector.defaultSeed("run-a"), SampleSelector.defaultSeed("run-a"));
        assertNotEquals(SampleSelector.defaultSeed("run-a"), SampleSelector.defaultSeed("run-b"));
        // Indices come back in the entries' order.
        final List<Integer> chosen = SampleSelector.select(entries, 50, 7);
        assertEquals(chosen.stream().sorted().toList(), chosen);
    }

    @Test
    void takesEverythingWhenTheInputIsSmallerThanTheSample() {
        final List<ShardEntry> entries = List.of(entry(0, "txt", 10), entry(1, "txt", 10), entry(2, "md", 9000));
        assertEquals(List.of(0, 1, 2), SampleSelector.select(entries, 50, 1));
        assertEquals(List.of(), SampleSelector.select(List.of(), 50, 1));
    }

    @Test
    void inventoryPutsTheSampleFirstOneEntryPerShard() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        for (int i = 0; i < 40; i++) {
            Fixtures.put(store, "in/" + (i % 2 == 0 ? "a" : "b") + i + (i % 3 == 0 ? ".md" : ".txt"),
                    "x".repeat(10 + i * 50));
        }
        final RunConfig config = Fixtures.config("sample: {enabled: true, size: 6}\ninventory: {shard_target_bytes: 2000}\n");
        final byte[] policy = Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8);
        final InventorySummary summary = new Inventory(store, config, TestClock.at("2026-11-26T00:00:00Z")).run(policy);
        assertEquals(6, summary.sampleShards());
        assertEquals(6, summary.sampleEntries());

        final RunLayout layout = new RunLayout(config);
        final List<Shard> shards = new ArrayList<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.shardsPrefix())) {
            shards.add(Json.read(store.get(layout.bucket(), o.key()).orElseThrow().data(), Shard.class));
        }
        long sampleBytes = 0;
        for (int i = 0; i < 6; i++) {
            assertEquals(1, shards.get(i).entries().size(), "a sample shard holds one entry");
            sampleBytes += shards.get(i).bytes();
        }
        assertEquals(sampleBytes, summary.sampleBytes());
        // Every entry is in exactly one shard.
        final List<String> keys = shards.stream().flatMap(s -> s.entries().stream()).map(ShardEntry::key).toList();
        assertEquals(40, keys.size());
        assertEquals(40, new HashSet<>(keys).size());
        // Both extensions are in the sample.
        final Set<String> extensions = new HashSet<>();
        shards.subList(0, 6).forEach(s -> extensions.add(s.entries().getFirst().extension()));
        assertEquals(Set.of("md", "txt"), extensions);

        // With the sample off there are no sample shards.
        final RunConfig off = Fixtures.config("run_id: no-sample\nsample: {enabled: false}\n");
        assertEquals(0, new Inventory(store, off, TestClock.at("2026-11-26T00:00:00Z")).run(policy).sampleShards());
    }

}
