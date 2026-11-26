/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.run.Formats.ShardEntry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Chooses the sample: random, but spread across file types and sizes, and the same every time for the same entries
 * and seed. Entries are grouped by extension and by size band (each band 16 times the one below), the groups are put
 * in a random order, and one entry is taken from each group in turn, at random within the group, until the sample is
 * full. So every type and size present gets a place before any gets a second one, as far as the size allows.
 */
public final class SampleSelector {

    private SampleSelector() {
    }

    /** The default seed for a run: derived from its ID, so rerunning the inventory chooses the same sample. */
    public static long defaultSeed(final String runId) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(("sample:" + runId)
                    .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The size band of an entry of this many bytes: 0 below 16 bytes, then one band per factor of 16. */
    static int sizeBand(final long bytes) {
        final int bits = 64 - Long.numberOfLeadingZeros(Math.max(1, bytes));
        return (bits - 1) / 4;
    }

    /**
     * The indices of the sampled entries, in the entries' order.
     * @param entries Every entry of the run, in inventory order.
     * @param size The most entries to choose.
     * @param seed The random seed.
     */
    public static List<Integer> select(final List<ShardEntry> entries, final int size, final long seed) {
        final Map<String, List<Integer>> groups = new TreeMap<>();
        for (int i = 0; i < entries.size(); i++) {
            final ShardEntry e = entries.get(i);
            final String group = e.extension() + "\u0000" + String.format("%02d", sizeBand(e.end() - e.start()));
            groups.computeIfAbsent(group, g -> new ArrayList<>()).add(i);
        }
        final Random random = new Random(seed);
        final List<List<Integer>> order = new ArrayList<>(groups.values());
        for (final List<Integer> group : order) {
            Collections.shuffle(group, random);
        }
        Collections.shuffle(order, random);

        final List<Integer> chosen = new ArrayList<>();
        for (int round = 0; chosen.size() < size; round++) {
            boolean any = false;
            for (final List<Integer> group : order) {
                if (round < group.size()) {
                    any = true;
                    chosen.add(group.get(round));
                    if (chosen.size() == size) {
                        break;
                    }
                }
            }
            if (!any) {
                break;
            }
        }
        Collections.sort(chosen);
        return chosen;
    }

}
