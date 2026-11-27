/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.Claim;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.Progress;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardCounts;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.ObjectStoreException;
import ai.philterd.batch.store.PreconditionFailedException;
import ai.philterd.batch.store.PutResult;
import ai.philterd.batch.store.StoredObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Hands out shards through the object store, with no other shared state. Every change of ownership is a
 * conditional write: a claim is created if absent, and renewed, taken over, or released only if its ETag still
 * matches. A shard's progress is written only if its ETag matches what this holder last wrote, so a holder that
 * lost its lease without knowing it cannot overwrite the new holder's progress. The protocol is specified in
 * {@code docs/formats.md}.
 */
public final class Coordinator {

    private static final Logger LOGGER = LogManager.getLogger(Coordinator.class);
    private static final String JSON = "application/json";

    /** Attempts to take a shard's progress object before giving the shard up. */
    static final int FENCE_ATTEMPTS = 10;

    private final ObjectStore store;
    private final RunLayout layout;
    private final RunConfig.Lease lease;
    private final StoreClock clock;
    private final Random random;
    private final Set<String> only;
    private List<String> shardIds;

    public Coordinator(final ObjectStore store, final RunLayout layout, final RunConfig.Lease lease,
                       final StoreClock clock, final Random random) {
        this(store, layout, lease, clock, random, null);
    }

    /**
     * @param only The shards this coordinator hands out, such as the sample's; null for every shard.
     */
    public Coordinator(final ObjectStore store, final RunLayout layout, final RunConfig.Lease lease,
                       final StoreClock clock, final Random random, final Set<String> only) {
        this.store = store;
        this.layout = layout;
        this.lease = lease;
        this.clock = clock;
        this.random = random;
        this.only = only == null ? null : Set.copyOf(only);
    }

    /** The shard IDs this coordinator hands out. Shards never change after inventory, so they are listed once. */
    public synchronized List<String> shardIds() {
        if (shardIds == null) {
            final List<String> ids = new ArrayList<>();
            for (final ObjectInfo o : store.list(layout.bucket(), layout.shardsPrefix())) {
                final String id = RunLayout.shardOf(o.key());
                if (only == null || only.contains(id)) {
                    ids.add(id);
                }
            }
            shardIds = List.copyOf(ids);
        }
        return shardIds;
    }

    /** The IDs of shards with a done marker. */
    public Set<String> doneIds() {
        final Set<String> done = new HashSet<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.donePrefix())) {
            done.add(RunLayout.shardOf(o.key()));
        }
        return done;
    }

    /** The lease for an attempt: the initial lease doubled for each earlier expiry, up to the maximum. */
    long leaseSeconds(final int attempt) {
        final int doublings = Math.min(Math.max(0, attempt - 1), 30);
        return Math.min((long) lease.maxSeconds(), (long) lease.initialSeconds() << doublings);
    }

    /**
     * Claims a shard with no done marker and no live claim, starting at a random shard so workers spread out.
     * Unclaimed shards are tried first, then claims that have expired or were released.
     */
    public ClaimResult claim(final String holder) {
        final List<String> ids = shardIds();
        final Set<String> done = doneIds();
        final Map<String, ObjectInfo> claims = new HashMap<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.claimsPrefix())) {
            claims.put(RunLayout.shardOf(o.key()), o);
        }
        final List<String> remaining = new ArrayList<>();
        for (final String id : ids) {
            if (!done.contains(id)) {
                remaining.add(id);
            }
        }
        if (remaining.isEmpty()) {
            return new ClaimResult.NoWorkLeft();
        }
        final int start = random.nextInt(remaining.size());
        final List<String> order = new ArrayList<>(remaining.size());
        for (int i = 0; i < remaining.size(); i++) {
            order.add(remaining.get((start + i) % remaining.size()));
        }

        for (final String id : order) {
            if (!claims.containsKey(id)) {
                final Optional<HeldShard> held = create(id, holder);
                if (held.isPresent()) {
                    return new ClaimResult.Claimed(held.get());
                }
            }
        }
        Instant nextExpiry = null;
        for (final String id : order) {
            if (claims.containsKey(id)) {
                final Optional<StoredObject> current = store.get(layout.bucket(), layout.claim(id));
                if (current.isEmpty()) {
                    continue;
                }
                clock.observe(current.get().serverTime());
                final Claim claim = readClaim(current.get().data());
                if (claim.released() || expired(claim)) {
                    final Optional<HeldShard> held = takeOver(id, holder, claim, current.get().etag());
                    if (held.isPresent()) {
                        return new ClaimResult.Claimed(held.get());
                    }
                } else if (nextExpiry == null || claim.expires().isBefore(nextExpiry)) {
                    nextExpiry = claim.expires();
                }
            }
        }
        return new ClaimResult.AllLeased(remaining.size(), nextExpiry);
    }

    /** Whether the claim's lease is over, by the store's clock, with the grace period. */
    boolean expired(final Claim claim) {
        return clock.now().isAfter(claim.expires().plusSeconds(lease.expiryGraceSeconds()));
    }

    private Optional<HeldShard> create(final String id, final String holder) {
        final Instant now = clock.now();
        final long seconds = leaseSeconds(1);
        final Claim claim = new Claim(Formats.FORMAT, id, holder, 1, 1, seconds, now, now.plusSeconds(seconds), 0, 0,
                false);
        try {
            final PutResult put = store.putIfAbsent(layout.bucket(), layout.claim(id), Json.write(claim), JSON);
            clock.observe(put.serverTime());
            LOGGER.info("Claimed shard {} (attempt 1, lease {}s).", id, seconds);
            return Optional.of(new HeldShard(readShard(id), holder, claim, put.etag()));
        } catch (final PreconditionFailedException e) {
            return Optional.empty();
        }
    }

    private Optional<HeldShard> takeOver(final String id, final String holder, final Claim old, final String etag) {
        final int cursor = currentCursor(id);
        final int attempt;
        final int stalled;
        if (old.released()) {
            // A holder that stopped cleanly did not fail: no doubling, and no evidence of a poison file.
            attempt = old.attempt();
            stalled = old.stalledAttempts();
        } else {
            attempt = old.attempt() + 1;
            stalled = cursor == old.cursorAtAcquire() ? old.stalledAttempts() + 1 : 0;
        }
        final Instant now = clock.now();
        final long seconds = leaseSeconds(attempt);
        final Claim claim = new Claim(Formats.FORMAT, id, holder, attempt, old.revision() + 1, seconds, now,
                now.plusSeconds(seconds), cursor, stalled, false);
        try {
            final PutResult put = store.putIfMatch(layout.bucket(), layout.claim(id), Json.write(claim), JSON, etag);
            clock.observe(put.serverTime());
            LOGGER.info("Took over shard {} (attempt {}, lease {}s, cursor {}, stalled {}).", id, attempt, seconds,
                    cursor, stalled);
            return Optional.of(new HeldShard(readShard(id), holder, claim, put.etag()));
        } catch (final PreconditionFailedException e) {
            return Optional.empty();
        }
    }

    private int currentCursor(final String id) {
        return store.get(layout.bucket(), layout.progress(id))
                .map(o -> readProgress(o.data()).cursor()).orElse(0);
    }

    /** Extends the lease from now. */
    public void renew(final HeldShard held) {
        updateClaim(held, held.claim().cursorAtAcquire(), held.claim().stalledAttempts(), false);
    }

    /** Records that the cursor moved past a quarantined poison file, so the stall count starts over. */
    public void resetStall(final HeldShard held, final int cursor) {
        updateClaim(held, cursor, 0, false);
    }

    /** Gives the shard up after saving progress, so another worker can take it without waiting for expiry. */
    public void release(final HeldShard held) {
        if (held.lost()) {
            return;
        }
        try {
            updateClaim(held, held.claim().cursorAtAcquire(), held.claim().stalledAttempts(), true);
            LOGGER.info("Released shard {}.", held.id());
        } catch (final LeaseLostException e) {
            // Someone else has it already.
        }
    }

    private void updateClaim(final HeldShard held, final int cursorAtAcquire, final int stalled,
                             final boolean release) {
        synchronized (held) {
            if (held.lost()) {
                throw new LeaseLostException("Shard " + held.id() + " was lost.");
            }
            final Claim old = held.claim();
            final Instant now = clock.now();
            final Claim next = new Claim(Formats.FORMAT, old.shard(), old.holder(), old.attempt(), old.revision() + 1,
                    old.leaseSeconds(), old.acquired(), release ? now : now.plusSeconds(old.leaseSeconds()),
                    cursorAtAcquire, stalled, release);
            try {
                final PutResult put = store.putIfMatch(layout.bucket(), layout.claim(held.id()), Json.write(next), JSON,
                        held.claimEtag());
                clock.observe(put.serverTime());
                held.claimWritten(next, put.etag());
            } catch (final PreconditionFailedException e) {
                held.markLost();
                throw new LeaseLostException("The claim on shard " + held.id() + " was changed by another worker.");
            }
        }
    }

    /**
     * Fails if this worker may no longer write for the shard: it learned the shard was lost, or its lease has run
     * out by the store's clock (renewals failing), in which case another worker may already have it.
     */
    public void checkHeld(final HeldShard held) {
        if (held.lost()) {
            throw new LeaseLostException("Shard " + held.id() + " was lost.");
        }
        if (!clock.now().isBefore(held.claim().expires())) {
            held.markLost();
            throw new LeaseLostException("The lease on shard " + held.id() + " ran out before it was renewed.");
        }
    }

    /**
     * Takes the shard's progress object: writes it with this holder's name, conditioned on its current ETag, so a
     * previous holder's later progress writes fail. Returns the progress to resume from.
     */
    public Progress fence(final HeldShard held) {
        for (int i = 0; i < FENCE_ATTEMPTS; i++) {
            checkHeld(held);
            final Optional<StoredObject> current = store.get(layout.bucket(), layout.progress(held.id()));
            try {
                final Progress next;
                final PutResult put;
                if (current.isEmpty()) {
                    next = new Progress(Formats.FORMAT, held.id(), held.holder(), held.claim().attempt(), 1, 0,
                            List.of(), List.of());
                    put = store.putIfAbsent(layout.bucket(), layout.progress(held.id()), Json.write(next), JSON);
                } else {
                    final Progress old = readProgress(current.get().data());
                    next = new Progress(Formats.FORMAT, held.id(), held.holder(), held.claim().attempt(),
                            old.revision() + 1, old.cursor(), old.results(), old.parts());
                    put = store.putIfMatch(layout.bucket(), layout.progress(held.id()), Json.write(next), JSON,
                            current.get().etag());
                }
                clock.observe(put.serverTime());
                held.progressWritten(put.etag());
                return next;
            } catch (final PreconditionFailedException e) {
                // A previous holder wrote progress between the read and the write. Its work counts; read again.
                LOGGER.debug("Progress of shard {} changed while fencing; retrying.", held.id());
            }
        }
        held.markLost();
        throw new LeaseLostException("Could not take the progress of shard " + held.id() + ".");
    }

    /** Saves progress, only if no one else has written it since this holder did. */
    public void saveProgress(final HeldShard held, final Progress progress) {
        checkHeld(held);
        try {
            final PutResult put = store.putIfMatch(layout.bucket(), layout.progress(held.id()), Json.write(progress),
                    JSON, held.progressEtag());
            clock.observe(put.serverTime());
            held.progressWritten(put.etag());
        } catch (final PreconditionFailedException e) {
            held.markLost();
            throw new LeaseLostException("The progress of shard " + held.id() + " was changed by another worker.");
        }
    }

    /**
     * Creates the shard's done marker if absent, then the shard's counts. Returns false if a done marker already
     * existed: another worker finished the same shard, and its parts are the ones finalize keeps.
     */
    public boolean complete(final HeldShard held, final DoneMarker done) {
        try {
            final PutResult put = store.putIfAbsent(layout.bucket(), layout.done(held.id()), Json.write(done), JSON);
            clock.observe(put.serverTime());
            LOGGER.info("Shard {} is done.", held.id());
        } catch (final PreconditionFailedException e) {
            LOGGER.info("Shard {} was already done by another worker.", held.id());
            return false;
        }
        // Only for status: a failure here leaves the shard counted from its done marker instead.
        try {
            store.put(layout.bucket(), layout.counts(held.id()),
                    Json.write(ShardCounts.of(done.runId(), held.id(), done.results())), JSON);
        } catch (final ObjectStoreException e) {
            LOGGER.warn("Writing the counts of shard {} failed; status reads its done marker instead.", held.id());
        }
        return true;
    }

    public Shard readShard(final String id) {
        final Shard shard = Json.read(store.get(layout.bucket(), layout.shard(id))
                .orElseThrow(() -> new FormatException("Shard " + id + " is missing.")).data(), Shard.class);
        FormatException.check("Shard " + id, shard.format(), Formats.FORMAT);
        return shard;
    }

    static Claim readClaim(final byte[] data) {
        final Claim claim = Json.read(data, Claim.class);
        FormatException.check("A claim", claim.format(), Formats.FORMAT);
        return claim;
    }

    static Progress readProgress(final byte[] data) {
        final Progress progress = Json.read(data, Progress.class);
        FormatException.check("A progress object", progress.format(), Formats.FORMAT);
        return progress;
    }

    public Duration renewInterval() {
        return Duration.ofSeconds(lease.renewSeconds());
    }

}
