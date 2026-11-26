/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

import ai.philterd.batch.run.Formats.Claim;
import ai.philterd.batch.run.Formats.Shard;

/** A shard this worker holds a claim on, with the ETags its next conditional writes must match. */
public final class HeldShard {

    private final Shard shard;
    private final String holder;
    private Claim claim;
    private String claimEtag;
    private String progressEtag;
    private volatile boolean lost;

    HeldShard(final Shard shard, final String holder, final Claim claim, final String claimEtag) {
        this.shard = shard;
        this.holder = holder;
        this.claim = claim;
        this.claimEtag = claimEtag;
    }

    public Shard shard() {
        return shard;
    }

    public String id() {
        return shard.shard();
    }

    public String holder() {
        return holder;
    }

    public synchronized Claim claim() {
        return claim;
    }

    synchronized String claimEtag() {
        return claimEtag;
    }

    synchronized void claimWritten(final Claim claim, final String etag) {
        this.claim = claim;
        this.claimEtag = etag;
    }

    synchronized String progressEtag() {
        return progressEtag;
    }

    synchronized void progressWritten(final String etag) {
        this.progressEtag = etag;
    }

    public boolean lost() {
        return lost;
    }

    void markLost() {
        lost = true;
    }

}
