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
    SEGMENT_QUARANTINED,
    /**
     * The content is not a type the run handles: a format with no Phileas service (such as a ZIP that is not a Word
     * document or Excel workbook, or a legacy {@code .doc}), a type not in {@code input_types.handled}, or a file
     * whose extension names a type with a signature (such as {@code .pdf}) that its content lacks.
     */
    UNSUPPORTED_CONTENT,
    /** The document is encrypted or password-protected. */
    ENCRYPTED,
    /** The document is not valid in its format. */
    MALFORMED,
    /** The document is over one of Phileas's limits, such as on pages or rows. */
    LIMIT_EXCEEDED;

    /**
     * The reason for a Phileas failure on a document of a type other than plain text. Matched by class name, so a
     * library's exception does not have to be on the compile path, and never by message, which could quote the
     * document.
     */
    public static ReasonCode forFailure(final Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            final String name = t.getClass().getName();
            switch (name) {
                case "ai.philterd.phileas.services.email.EncryptedEmailException",
                     "org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException",
                     "org.apache.poi.EncryptedDocumentException" -> {
                    return ENCRYPTED;
                }
                case "ai.philterd.phileas.services.pdf.PdfLimitExceededException",
                     "ai.philterd.phileas.services.csv.CsvLimitExceededException" -> {
                    return LIMIT_EXCEEDED;
                }
                case "ai.philterd.phileas.services.rtf.RtfFormatException",
                     "org.apache.poi.openxml4j.exceptions.InvalidFormatException",
                     "org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException",
                     "org.apache.poi.ooxml.POIXMLException",
                     "jakarta.mail.MessagingException" -> {
                    return MALFORMED;
                }
                default -> {
                }
            }
        }
        // The services reject input that is not valid in their format with IllegalArgumentException.
        return failure instanceof IllegalArgumentException ? MALFORMED : REDACTION_FAILED;
    }
}
