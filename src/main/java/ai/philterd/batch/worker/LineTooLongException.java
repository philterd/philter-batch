/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

/** A segment's last line does not end within inventory.max_line_bytes of the segment's end. */
public class LineTooLongException extends Exception {

    public LineTooLongException() {
        super("The segment's last line is longer than inventory.max_line_bytes.");
    }

}
