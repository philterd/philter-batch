/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.coordination;

/** This process's clock differs from the store's by more than lease.max_clock_skew_seconds. */
public class ClockSkewException extends RuntimeException {

    public ClockSkewException(final String message) {
        super(message);
    }

}
