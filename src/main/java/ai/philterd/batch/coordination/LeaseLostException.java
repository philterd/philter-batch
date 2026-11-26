/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

/** This worker no longer holds the shard: its claim or progress was changed by another, or its lease ran out. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(final String message) {
        super(message);
    }

}
