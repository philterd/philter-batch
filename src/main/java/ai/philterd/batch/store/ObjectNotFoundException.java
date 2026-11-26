/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

/** The object does not exist. */
public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(final String message) {
        super(message);
    }

}
