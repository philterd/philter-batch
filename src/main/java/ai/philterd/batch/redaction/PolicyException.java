/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

/** The policy is not one Philter Batch can run. */
public class PolicyException extends RuntimeException {

    public PolicyException(final String message) {
        super(message);
    }

}
