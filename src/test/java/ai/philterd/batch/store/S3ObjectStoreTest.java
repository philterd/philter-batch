/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ObjectStoreTest {

    private static final long MIB = 1024 * 1024;

    @Test
    void parsesTheDateHeader() {
        assertEquals(Instant.parse("2026-11-26T00:51:22Z"), S3ObjectStore.parseDate("Thu, 26 Nov 2026 00:51:22 GMT"));
        assertNull(S3ObjectStore.parseDate("yesterday"));
    }

    @Test
    void serverSideCopyNeedsLargeEnoughParts() {
        assertTrue(S3ObjectStore.serverSideCopyPossible(List.of(5 * MIB, 6 * MIB, 1L)));
        assertFalse(S3ObjectStore.serverSideCopyPossible(List.of(5 * MIB - 1, 6 * MIB)));
        assertTrue(S3ObjectStore.serverSideCopyPossible(List.of(1L)));
    }

    @Test
    void copyRangesAreEvenAndCoverTheSource() {
        final long size = 2500 * MIB;
        final List<long[]> ranges = S3ObjectStore.copyRanges(size);
        assertEquals(3, ranges.size());
        long next = 0;
        for (final long[] r : ranges) {
            assertEquals(next, r[0]);
            assertTrue(r[1] - r[0] + 1 <= S3ObjectStore.MAX_COPY_PART_BYTES);
            assertTrue(r[1] - r[0] + 1 >= S3ObjectStore.MIN_PART_BYTES);
            next = r[1] + 1;
        }
        assertEquals(size, next);
        assertEquals(1, S3ObjectStore.copyRanges(10).size());
    }

}
