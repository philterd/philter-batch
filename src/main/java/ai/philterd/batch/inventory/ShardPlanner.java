/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.run.Formats.ShardEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs entries, in order, into shards of about the target size. Order is kept so the plan is the same every time
 * inventory runs on the same listing, and a segmented file's segments stay next to each other.
 */
public final class ShardPlanner {

    private ShardPlanner() {
    }

    public static List<List<ShardEntry>> plan(final List<ShardEntry> entries, final long targetBytes,
                                              final int maxEntries) {
        final List<List<ShardEntry>> shards = new ArrayList<>();
        List<ShardEntry> current = new ArrayList<>();
        long bytes = 0;
        for (final ShardEntry entry : entries) {
            final long size = entry.end() - entry.start();
            if (!current.isEmpty() && (bytes + size > targetBytes || current.size() >= maxEntries)) {
                shards.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(entry);
            bytes += size;
        }
        if (!current.isEmpty()) {
            shards.add(current);
        }
        return shards;
    }

}
