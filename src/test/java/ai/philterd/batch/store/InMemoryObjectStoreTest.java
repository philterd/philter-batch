/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import ai.philterd.batch.TestClock;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryObjectStoreTest {

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void enforcesConditionalWritesLikeS3() {
        final InMemoryObjectStore store = new InMemoryObjectStore(TestClock.at("2026-11-25T00:00:00Z"));
        final PutResult first = store.putIfAbsent("b", "k", b("one"), "text/plain");
        assertThrows(PreconditionFailedException.class, () -> store.putIfAbsent("b", "k", b("two"), "text/plain"));
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("b", "k", b("two"), "text/plain", "\"x\""));
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("b", "absent", b("two"), "text/plain",
                first.etag()));
        final PutResult second = store.putIfMatch("b", "k", b("two"), "text/plain", first.etag());
        assertNotEquals(first.etag(), second.etag());
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("b", "k", b("three"), "text/plain",
                first.etag()));
        assertEquals("two", new String(store.get("b", "k").orElseThrow().data(), StandardCharsets.UTF_8));
        assertEquals(TestClock.at("2026-11-25T00:00:00Z").instant(), second.serverTime());
    }

    @Test
    void sameBytesGiveTheSameEtagAsOnS3() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        final PutResult a = store.put("b", "k", b("same"), "text/plain");
        final PutResult c = store.put("b", "k", b("same"), "text/plain");
        assertEquals(a.etag(), c.etag());
    }

    @Test
    void getIfMatchAndRanges() throws Exception {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        final String etag = store.put("b", "k", b("0123456789"), "text/plain").etag();
        assertThrows(PreconditionFailedException.class, () -> store.getIfMatch("b", "k", "\"x\""));
        assertThrows(ObjectNotFoundException.class, () -> store.getIfMatch("b", "nope", etag));
        try (InputStream in = store.openRange("b", "k", etag, 3, 5)) {
            assertEquals("345", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = store.openRange("b", "k", etag, 8, 100)) {
            assertEquals("89", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = store.openRange("b", "k", etag, 50, 100)) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void listsByPrefixInKeyOrder() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        store.put("b", "p/2", b("x"), "text/plain");
        store.put("b", "p/1", b("yy"), "text/plain");
        store.put("b", "q/1", b("z"), "text/plain");
        store.put("other", "p/3", b("z"), "text/plain");
        final List<ObjectInfo> list = store.list("b", "p/");
        assertEquals(List.of("p/1", "p/2"), list.stream().map(ObjectInfo::key).toList());
        assertEquals(2, list.getFirst().size());
        store.delete("b", "p/1");
        store.delete("b", "never");
        assertEquals(1, store.list("b", "p/").size());
    }

    @Test
    void composesInOrder() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        store.put("b", "a", b("ab"), "text/plain");
        store.put("b", "c", b("cd"), "text/plain");
        store.compose("b", "t", List.of("c", "a"), "text/plain");
        assertArrayEquals(b("cdab"), store.get("b", "t").orElseThrow().data());
        assertThrows(ObjectNotFoundException.class, () -> store.compose("b", "t", List.of("missing"), "text/plain"));
    }

    @Test
    void injectedFaults() {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        store.setFault((op, key) -> op.equals("put") && key.endsWith("/bad"));
        assertThrows(ObjectStoreException.class, () -> store.put("b", "bad", b("x"), "text/plain"));
        store.put("b", "good", b("x"), "text/plain");
        store.setFault(null);
        store.put("b", "bad", b("x"), "text/plain");
    }

    @Test
    void exactlyOneConcurrentCreateWins() throws Exception {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        final int threads = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 50; round++) {
                final String key = "k" + round;
                final CyclicBarrier barrier = new CyclicBarrier(threads);
                final AtomicInteger wins = new AtomicInteger();
                final List<Future<?>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int id = t;
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        try {
                            store.putIfAbsent("b", key, b("w" + id), "text/plain");
                            wins.incrementAndGet();
                        } catch (final PreconditionFailedException e) {
                            // lost
                        }
                        return null;
                    }));
                }
                for (final Future<?> f : futures) {
                    f.get();
                }
                assertEquals(1, wins.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(store.list("b", "k").size() >= 50);
    }

}
