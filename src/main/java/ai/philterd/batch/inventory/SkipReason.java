/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

/** Why inventory left an input object out. A skipped object is never read or copied. */
public enum SkipReason {
    /** Not a type this version handles (not one of the plain-text extensions). */
    UNSUPPORTED_TYPE,
    /** Larger than inventory.max_file_bytes. */
    TOO_LARGE,
    /** Zero bytes. */
    EMPTY_FILE,
    /** A key ending in a slash. */
    DIRECTORY_MARKER
}
