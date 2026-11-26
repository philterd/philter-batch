/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.store.ObjectStore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Reads one segment of a large file: the lines that start at or after the segment's {@code start} and before its
 * {@code end}, each through its newline. Segments of a file therefore cover every line exactly once without
 * inventory reading the file. A segment's last line may run past {@code end}, by up to {@code maxLineBytes}.
 */
public final class SegmentReader {

    private SegmentReader() {
    }

    public static byte[] read(final ObjectStore store, final String bucket, final ShardEntry entry,
                              final long maxLineBytes) throws IOException, LineTooLongException {
        // One byte before the start shows whether a line starts exactly at the start.
        final long from = entry.start() == 0 ? 0 : entry.start() - 1;
        final long to = Math.min(entry.size(), entry.end() + maxLineBytes) - 1;
        final byte[] data;
        try (InputStream in = store.openRange(bucket, entry.key(), entry.etag(), from, to)) {
            data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, to - from + 1));
        }
        return slice(data, from, entry, from + data.length >= entry.size());
    }

    /**
     * The segment's lines within {@code data}, which holds the file from byte {@code from}.
     * @param atEof Whether {@code data} reaches the end of the file.
     */
    static byte[] slice(final byte[] data, final long from, final ShardEntry entry, final boolean atEof)
            throws LineTooLongException {
        final int segmentEnd = (int) (entry.end() - from);
        int begin;
        if (entry.start() == 0) {
            begin = 0;
        } else {
            // data[0] is the byte before start; the first line starting in the segment follows the first newline.
            final int newline = indexOf(data, 0, segmentEnd);
            if (newline < 0) {
                return new byte[0];
            }
            begin = newline + 1;
        }
        if (begin >= segmentEnd) {
            return new byte[0];
        }
        // The last line starting before end runs through the first newline at or after end - 1.
        final int last = indexOf(data, segmentEnd - 1, data.length);
        final int stop;
        if (last >= 0) {
            stop = last + 1;
        } else if (atEof) {
            stop = data.length;
        } else {
            throw new LineTooLongException();
        }
        return Arrays.copyOfRange(data, begin, stop);
    }

    private static int indexOf(final byte[] data, final int from, final int to) {
        for (int i = Math.max(0, from); i < Math.min(to, data.length); i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

}
