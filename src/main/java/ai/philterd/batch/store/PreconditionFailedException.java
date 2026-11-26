/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

/**
 * A conditional request found the object not in the expected state: it already existed for a create-if-absent,
 * or it was missing or had a different ETag for a write-if-match. S3 answers these with 412, 404, or 409.
 */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(final String message) {
        super(message);
    }

}
