/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentReaderTest {

    @SuppressWarnings("unchecked")
    private static List<ShardEntry> segments(final ObjectInfo info, final long segmentBytes) throws Exception {
        final Method m = Inventory.class.getDeclaredMethod("segments", String.class, ObjectInfo.class, String.class,
                long.class);
        m.setAccessible(true);
        return (List<ShardEntry>) m.invoke(null, "doc", info, "txt", segmentBytes);
    }

    private static byte[] readAll(final String text, final long segmentBytes, final long maxLine) throws Exception {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        final byte[] data = text.getBytes(StandardCharsets.UTF_8);
        final String etag = store.put("b", "k", data, "text/plain").etag();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final ShardEntry e : segments(new ObjectInfo("k", data.length, etag), segmentBytes)) {
            out.writeBytes(SegmentReader.read(store, "b", e, maxLine));
        }
        return out.toByteArray();
    }

    @Test
    void segmentsCoverEveryLineExactlyOnce() throws Exception {
        final Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            final StringBuilder sb = new StringBuilder();
            final int lines = random.nextInt(40);
            for (int i = 0; i < lines; i++) {
                sb.append("é".repeat(random.nextInt(3))).append("x".repeat(random.nextInt(12)));
                if (i < lines - 1 || random.nextBoolean()) {
                    sb.append('\n');
                }
            }
            final String text = sb.toString();
            final long segment = 1 + random.nextInt(25);
            assertEquals(text, new String(readAll(text, segment, 1000), StandardCharsets.UTF_8),
                    "round " + round + " segment " + segment);
        }
    }

    @Test
    void edgeCases() throws Exception {
        assertEquals("a\nb\n", new String(readAll("a\nb\n", 2, 10), StandardCharsets.UTF_8));
        assertEquals("\n\n\n", new String(readAll("\n\n\n", 1, 10), StandardCharsets.UTF_8));
        assertEquals("abc", new String(readAll("abc", 1, 10), StandardCharsets.UTF_8));
    }

    @Test
    void aLineLongerThanTheLimitFails() throws Exception {
        final String text = "short\n" + "y".repeat(100) + "\nend\n";
        assertThrows(LineTooLongException.class, () -> readAll(text, 10, 20));
    }

    @Test
    void sliceWithNoLineStartIsEmpty() throws Exception {
        final ShardEntry middle = new ShardEntry("d", "k", "e", 30, "txt", 1, 3, 10, 20);
        final byte[] data = "x".repeat(30).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(new byte[0], SegmentReader.slice(java.util.Arrays.copyOfRange(data, 9, 30), 9, middle, true));
    }

}
