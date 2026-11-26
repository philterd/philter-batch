/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

import java.time.Instant;

/** What a worker found when it looked for a shard. */
public sealed interface ClaimResult {

    /** It claimed a shard. */
    record Claimed(HeldShard shard) implements ClaimResult {
    }

    /** Every shard has a done marker. */
    record NoWorkLeft() implements ClaimResult {
    }

    /** Shards remain, but each has a live claim. {@code nextExpiry} is the earliest expiry, by the store's clock. */
    record AllLeased(int remaining, Instant nextExpiry) implements ClaimResult {
    }

}
