/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

/** Why a document or segment was quarantined. A quarantined input produces no output of any kind. */
public enum ReasonCode {
    /** The input changed after inventory (its ETag no longer matches). */
    CHANGED_SINCE_INVENTORY,
    /** The input was deleted after inventory. */
    INPUT_MISSING,
    /** The bytes are not valid in the configured character set. */
    UNDECODABLE,
    /** A segment's last line ran past inventory.max_line_bytes. */
    LINE_TOO_LONG,
    /** Phileas failed on the text. */
    REDACTION_FAILED,
    /** Redaction took longer than worker.file_timeout_seconds. */
    TIMEOUT,
    /** Phileas's leftover check still detected values in the redacted text. */
    LEFTOVER_FOUND,
    /** The redacted text could not be encoded in the configured character set for document output. */
    ENCODING_FAILED,
    /** Workers kept dying on this entry (the cursor did not move across lease.poison_attempts leases). */
    POISON,
    /** Document output only: another segment of the same file was quarantined, so the file is not written. */
    SEGMENT_QUARANTINED
}
