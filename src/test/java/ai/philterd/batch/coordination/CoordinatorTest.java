/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.Claim;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.Progress;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorTest {

    private final TestClock storeClock = TestClock.at("2026-11-25T12:00:00Z");
    private final InMemoryObjectStore store = new InMemoryObjectStore(storeClock);
    private final RunConfig config = Fixtures.config("inventory: {shard_target_bytes: 10}\n");
    private final RunLayout layout = new RunLayout(config);

    private void inventory(final int files) {
        for (int i = 0; i < files; i++) {
            Fixtures.put(store, "in/f" + i + ".txt", "0123456789");
        }
        new Inventory(store, config, storeClock).run(Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8));
    }

    /** A coordinator for a worker whose own clock is {@code local}. */
    private Coordinator coordinator(final Clock local, final RunConfig.Lease lease, final long seed) {
        final StoreClock clock = new StoreClock(store, layout.bucket(), local,
                Duration.ofSeconds(lease.maxClockSkewSeconds()));
        return new Coordinator(store, layout, lease, clock, new Random(seed));
    }

    private Coordinator coordinator(final long seed) {
        return coordinator(storeClock, config.lease(), seed);
    }

    private static HeldShard claimed(final ClaimResult result) {
        return assertInstanceOf(ClaimResult.Claimed.class, result).shard();
    }

    private Claim claimObject(final String id) {
        return Json.read(store.get(layout.bucket(), layout.claim(id)).orElseThrow().data(), Claim.class);
    }

    @Test
    void twoClaimantsRacingForOneShardOneWins() throws Exception {
        inventory(1);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 100; round++) {
                store.delete(layout.bucket(), layout.claim("000000"));
                final CyclicBarrier barrier = new CyclicBarrier(8);
                final List<Future<ClaimResult>> results = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    final Coordinator c = coordinator(round * 31L + t);
                    final String holder = "w" + t;
                    results.add(pool.submit(() -> {
                        barrier.await();
                        return c.claim(holder);
                    }));
                }
                int won = 0;
                int leased = 0;
                for (final Future<ClaimResult> f : results) {
                    final ClaimResult r = f.get();
                    if (r instanceof ClaimResult.Claimed) {
                        won++;
                    } else if (r instanceof ClaimResult.AllLeased) {
                        leased++;
                    }
                }
                assertEquals(1, won, "round " + round);
                assertEquals(7, leased, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyWorkersClaimEachShardOnce() throws Exception {
        inventory(20);
        final Set<String> claimed = ConcurrentHashMap.newKeySet();
        final ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            final CyclicBarrier barrier = new CyclicBarrier(10);
            final List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < 10; t++) {
                final Coordinator c = coordinator(t);
                final String holder = "w" + t;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    int mine = 0;
                    while (c.claim(holder) instanceof ClaimResult.Claimed got) {
                        assertTrue(claimed.add(got.shard().id()), "shard claimed twice: " + got.shard().id());
                        mine++;
                    }
                    return mine;
                }));
            }
            int total = 0;
            for (final Future<Integer> f : futures) {
                total += f.get();
            }
            assertEquals(20, total);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void takeoverOnlyAfterExpiryAndGrace() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final Coordinator b = coordinator(2);
        final HeldShard held = claimed(a.claim("a"));
        assertEquals(60, held.claim().leaseSeconds());

        storeClock.advance(Duration.ofSeconds(60 + 5));
        assertInstanceOf(ClaimResult.AllLeased.class, b.claim("b"), "still within the grace period");
        storeClock.advance(Duration.ofSeconds(1));
        final HeldShard taken = claimed(b.claim("b"));
        assertEquals("b", taken.holder());
        assertEquals(2, taken.claim().attempt());
        assertEquals(120, taken.claim().leaseSeconds(), "the lease doubles with each expired attempt");

        // The old holder finds out on its next renewal or write.
        assertThrows(LeaseLostException.class, () -> a.renew(held));
        assertTrue(held.lost());
        assertThrows(LeaseLostException.class, () -> a.checkHeld(held));
    }

    @Test
    void renewalKeepsTheShard() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final Coordinator b = coordinator(2);
        final HeldShard held = claimed(a.claim("a"));
        for (int i = 0; i < 20; i++) {
            storeClock.advance(Duration.ofSeconds(10));
            a.renew(held);
            assertInstanceOf(ClaimResult.AllLeased.class, b.claim("b"));
        }
        assertEquals(21, claimObject("000000").revision());
    }

    @Test
    void anUnrenewedLeaseStopsTheHolderWriting() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final HeldShard held = claimed(a.claim("a"));
        a.fence(held);
        storeClock.advance(Duration.ofSeconds(61));
        final LeaseLostException e = assertThrows(LeaseLostException.class, () -> a.saveProgress(held,
                new Progress(Formats.FORMAT, held.id(), "a", 1, 9, 1, List.of(), List.of())));
        assertTrue(e.getMessage().contains("ran out"));
    }

    @Test
    void fencingStopsAStaleHoldersProgressWrites() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final Coordinator b = coordinator(2);
        final HeldShard heldA = claimed(a.claim("a"));
        final Progress startA = a.fence(heldA);
        a.saveProgress(heldA, new Progress(Formats.FORMAT, heldA.id(), "a", 1, startA.revision() + 1, 1, List.of(),
                List.of("part-a")));

        // A stalls (a long GC pause) past expiry; B takes over and fences.
        storeClock.advance(Duration.ofSeconds(70));
        final HeldShard heldB = claimed(b.claim("b"));
        final Progress startB = b.fence(heldB);
        assertEquals(1, startB.cursor(), "B resumes from A's saved cursor");
        assertEquals(List.of("part-a"), startB.parts());

        // A wakes and writes progress without noticing the lost lease (its clock check is bypassed by writing
        // directly through the store with its last ETag): the conditional write fails.
        final Progress stale = new Progress(Formats.FORMAT, heldA.id(), "a", 1, 99, 2, List.of(), List.of("part-a"));
        assertThrows(ai.philterd.batch.store.PreconditionFailedException.class, () -> store.putIfMatch(layout.bucket(),
                layout.progress(heldA.id()), Json.write(stale), "application/json", heldA.progressEtag()));
        b.saveProgress(heldB, new Progress(Formats.FORMAT, heldB.id(), "b", 2, startB.revision() + 1, 2, List.of(),
                List.of("part-a", "part-b")));
    }

    @Test
    void fenceRetriesWhenTheOldHolderWritesInBetween() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final HeldShard heldA = claimed(a.claim("a"));
        a.fence(heldA);
        storeClock.advance(Duration.ofSeconds(70));
        final Coordinator b = coordinator(2);
        final HeldShard heldB = claimed(b.claim("b"));
        // A's last write lands between B's claim and B's fence: B's fence rereads and keeps A's progress.
        store.putIfMatch(layout.bucket(), layout.progress(heldA.id()), Json.write(new Progress(Formats.FORMAT,
                heldA.id(), "a", 1, 5, 3, List.of(), List.of("p1"))), "application/json", heldA.progressEtag());
        final Progress p = b.fence(heldB);
        assertEquals(3, p.cursor());
        assertEquals("b", p.holder());
    }

    @Test
    void releasedShardsAreTakenAtOnceWithoutDoubling() {
        inventory(1);
        final Coordinator a = coordinator(1);
        final Coordinator b = coordinator(2);
        final HeldShard held = claimed(a.claim("a"));
        a.release(held);
        assertTrue(claimObject("000000").released());
        final HeldShard taken = claimed(b.claim("b"));
        assertEquals(1, taken.claim().attempt());
        assertEquals(60, taken.claim().leaseSeconds());
        assertEquals(0, taken.claim().stalledAttempts());
        a.release(held);
    }

    @Test
    void stallsAreCountedOnlyWhenTheCursorDidNotMove() {
        inventory(1);
        final Coordinator a = coordinator(1);
        HeldShard held = claimed(a.claim("w1"));
        a.fence(held);
        // w1 dies without moving the cursor.
        storeClock.advance(Duration.ofSeconds(70));
        held = claimed(coordinator(2).claim("w2"));
        assertEquals(1, held.claim().stalledAttempts());
        final Coordinator c2 = coordinator(2);
        final Progress p = c2.fence(held);
        // w2 moves the cursor, then dies.
        c2.saveProgress(held, new Progress(Formats.FORMAT, held.id(), "w2", 2, p.revision() + 1, 1, List.of(), List.of()));
        storeClock.advance(Duration.ofSeconds(200));
        held = claimed(coordinator(3).claim("w3"));
        assertEquals(0, held.claim().stalledAttempts());
        assertEquals(1, held.claim().cursorAtAcquire());
        assertEquals(3, held.claim().attempt());
        assertEquals(240, held.claim().leaseSeconds());
        coordinator(3).resetStall(held, 1);
    }

    @Test
    void leaseDoublingIsCapped() {
        final RunConfig capped = Fixtures.config("lease: {initial_seconds: 60, max_seconds: 300, renew_seconds: 10}\n");
        final Coordinator c = new Coordinator(store, layout, capped.lease(), null, new Random(1));
        assertEquals(60, c.leaseSeconds(1));
        assertEquals(120, c.leaseSeconds(2));
        assertEquals(240, c.leaseSeconds(3));
        assertEquals(300, c.leaseSeconds(4));
        assertEquals(300, c.leaseSeconds(1000));
    }

    @Test
    void doneShardsAreNotClaimedAndOnlyOneDoneMarkerExists() {
        inventory(2);
        final Coordinator a = coordinator(1);
        final HeldShard first = claimed(a.claim("a"));
        final DoneMarker done = new DoneMarker(Formats.FORMAT, "test-run", first.id(), "a", 1, storeClock.instant(),
                "p", "4.5.0", "1.1.0", List.of(), "text", List.of(), List.of());
        assertTrue(a.complete(first, done));
        assertFalse(a.complete(first, done), "a second done marker is refused");
        final HeldShard second = claimed(a.claim("a"));
        assertFalse(second.id().equals(first.id()));
        a.complete(second, done);
        assertInstanceOf(ClaimResult.NoWorkLeft.class, a.claim("a"));
        assertEquals(Set.of("000000", "000001"), a.doneIds());
    }

    @Test
    void workerClockBehindTheStoreStillTimesLeasesByTheStore() {
        inventory(1);
        final RunConfig.Lease lenient = Fixtures.config("lease: {initial_seconds: 60, renew_seconds: 10, "
                + "max_clock_skew_seconds: 7200}\n").lease();
        // A's clock is an hour slow; B's is an hour fast.
        final TestClock slow = new TestClock(storeClock.instant().minus(Duration.ofHours(1)));
        final TestClock fast = new TestClock(storeClock.instant().plus(Duration.ofHours(1)));
        final Coordinator a = coordinator(slow, lenient, 1);
        final Coordinator b = coordinator(fast, lenient, 2);
        final HeldShard held = claimed(a.claim("a"));
        assertEquals(storeClock.instant().plusSeconds(60), held.claim().expires(), "expiry is in store time");

        // B's fast clock does not make it think A's lease expired.
        assertInstanceOf(ClaimResult.AllLeased.class, b.claim("b"));
        // Time passes everywhere; A's renewals keep the lease alive by the store's clock.
        for (int i = 0; i < 10; i++) {
            storeClock.advance(Duration.ofSeconds(10));
            slow.advance(Duration.ofSeconds(10));
            fast.advance(Duration.ofSeconds(10));
            a.renew(held);
            assertInstanceOf(ClaimResult.AllLeased.class, b.claim("b"));
        }
        // A stops renewing: B takes over only once the store's clock passes the expiry.
        storeClock.advance(Duration.ofSeconds(60));
        slow.advance(Duration.ofSeconds(60));
        fast.advance(Duration.ofSeconds(60));
        assertInstanceOf(ClaimResult.AllLeased.class, b.claim("b"));
        storeClock.advance(Duration.ofSeconds(6));
        slow.advance(Duration.ofSeconds(6));
        fast.advance(Duration.ofSeconds(6));
        assertEquals("b", claimed(b.claim("b")).holder());
    }

    @Test
    void aLargeClockDifferenceIsRefused() {
        inventory(1);
        final TestClock off = new TestClock(storeClock.instant().plus(Duration.ofMinutes(10)));
        final Coordinator c = coordinator(off, config.lease(), 1);
        assertThrows(ClockSkewException.class, () -> c.claim("a"));
        final StoreClock clock = new StoreClock(store, layout.bucket(), off, Duration.ofMinutes(20));
        clock.sync();
        assertEquals(-600, clock.offset().toSeconds());
        clock.observe(null);
        assertEquals(-600, clock.offset().toSeconds());
    }

    @Test
    void noShardsMeansNoWork() {
        new Inventory(store, config, storeClock).run(Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(ClaimResult.NoWorkLeft.class, coordinator(1).claim("a"));
    }

}
