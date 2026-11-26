/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.config;

/** The configuration is missing a setting or has an invalid one. */
public class ConfigException extends RuntimeException {

    public ConfigException(final String message) {
        super(message);
    }

    public ConfigException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
