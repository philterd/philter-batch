/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

import ai.philterd.batch.store.ObjectStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The store's clock, which every lease is measured by: this process's clock plus the offset to the store's
 * {@code Date} header, refreshed from every response that carries one. A worker whose own clock is wrong still
 * times leases correctly; one that is wrong by more than the configured limit is refused, as the Migration
 * Assistant refuses one, since it points at a misconfigured node.
 */
public final class StoreClock {

    private final ObjectStore store;
    private final String bucket;
    private final Clock local;
    private final Duration maxSkew;
    private volatile Duration offset;

    public StoreClock(final ObjectStore store, final String bucket, final Clock local, final Duration maxSkew) {
        this.store = store;
        this.bucket = bucket;
        this.local = local;
        this.maxSkew = maxSkew;
    }

    /** Updates the offset from a store response's time. Ignores null (a response with no {@code Date}). */
    public void observe(final Instant serverTime) {
        if (serverTime == null) {
            return;
        }
        final Duration skew = Duration.between(local.instant(), serverTime);
        if (skew.abs().compareTo(maxSkew) > 0) {
            throw new ClockSkewException("This process's clock differs from the store's by " + skew.toSeconds()
                    + " seconds, more than lease.max_clock_skew_seconds (" + maxSkew.toSeconds() + ").");
        }
        offset = skew;
    }

    /** Asks the store for its time. */
    public void sync() {
        observe(store.serverTime(bucket));
    }

    /** The store's current time, as best known. */
    public Instant now() {
        if (offset == null) {
            sync();
        }
        return local.instant().plus(offset);
    }

    /** The offset last measured, store minus local; zero before the first measurement. */
    public Duration offset() {
        return offset == null ? Duration.ZERO : offset;
    }

}
