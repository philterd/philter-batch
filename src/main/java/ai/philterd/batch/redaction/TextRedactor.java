/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.run.Formats.Replacement;

import java.util.List;
import java.util.Map;

/** Redacts one text or document. Implementations are safe to call from several threads. */
public interface TextRedactor {

    /**
     * The redacted text.
     * @param text The redacted text.
     * @param piiCounts Values replaced, by filter type.
     * @param leftoverChecked Whether Phileas's leftover check ran (the policy's {@code config.review.leftoverCheck}).
     * @param leftoverCounts What the leftover check still detected in {@code text}, by filter type. Empty when it
     *                       found nothing or did not run.
     * @param replacements Where the replacements are in {@code text}, by type, in order; null when they could not
     *                     be placed exactly.
     */
    record Redaction(String text, Map<String, Integer> piiCounts, boolean leftoverChecked,
                     Map<String, Integer> leftoverCounts, List<Replacement> replacements) {
    }

    Redaction redact(String text) throws Exception;

    /**
     * One section of a document's redacted text, as Phileas labels it.
     * @param label Phileas's label, such as {@code page 1} or {@code sheet: People}. It can hold a name from the
     *              document, such as a sheet's or an attachment's.
     * @param status {@code text}, {@code no_text}, or {@code not_scanned}.
     */
    record Section(String label, String status, String text) {
    }

    /**
     * A redacted document: its text, in text output, or the redacted file, in document output.
     * @param sections The redacted text by section; null in document output.
     * @param document The redacted file; null in text output.
     */
    record DocumentRedaction(List<Section> sections, byte[] document, Map<String, Integer> piiCounts,
                             boolean leftoverChecked, Map<String, Integer> leftoverCounts) {
    }

    /**
     * Redacts a document of a type other than plain text with the Phileas service for its type.
     * @param type The type, detected from the content.
     * @param data The whole file.
     * @param textOutput Return the redacted text rather than the redacted file.
     */
    default DocumentRedaction redactDocument(final DocumentType type, final byte[] data, final boolean textOutput)
            throws Exception {
        throw new UnsupportedOperationException("This redactor redacts plain text only.");
    }

}
