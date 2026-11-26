/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

/**
 * A store request failed for a reason other than a failed precondition or a missing object. The message carries
 * the status and error code only: store error messages can echo object keys, and keys can contain names.
 */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
