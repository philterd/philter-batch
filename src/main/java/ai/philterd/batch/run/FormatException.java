/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

/** A coordination object could not be read, or has a format version this build does not know. */
public class FormatException extends RuntimeException {

    public FormatException(final String message) {
        super(message);
    }

    public FormatException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /** Refuses a format version newer than this build writes. */
    public static void check(final String what, final int format, final int supported) {
        if (format < 1 || format > supported) {
            throw new FormatException(what + " has format " + format + "; this build reads format " + supported + ".");
        }
    }

}
