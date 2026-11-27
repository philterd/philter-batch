/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.DocumentType;
import ai.philterd.phileas.services.email.EmailRedactor;
import ai.philterd.phileas.services.ooxml.OoxmlDocumentType;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Detects a file's type from its content, with the detectors the Phileas services themselves use: Phileas's
 * {@code OoxmlDocumentType.of} for Word and Excel, the OLE2 signature {@code EmailRedactor.isOutlookMessage} checks
 * for Outlook messages, and the fixed signatures of PDF ({@code %PDF-}) and RTF (<code>{\rtf</code>). Detection
 * that agrees with the services means a file is sent only to a service that accepts its format. Tika was not used:
 * {@code tika-core} without its parsers cannot tell a Word document from an Excel workbook, which are both ZIP files.
 *
 * <p>Plain text, CSV, and MIME email have no signature, so a file without one is the type its extension names.
 * OLE2 is also the container of legacy {@code .doc} and {@code .xls} files and of encrypted Word and Excel files,
 * so an OLE2 file is an Outlook message only when its extension names email.</p>
 */
public final class DocumentTypes {

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RTF = "{\\rtf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    /** PDF readers accept the header anywhere in the first 1,024 bytes. */
    private static final int PDF_HEADER_WINDOW = 1024;

    private DocumentTypes() {
    }

    /**
     * The file's type.
     * @param data The whole file.
     * @param byExtension The type its extension names.
     * @return The type, or empty when the content is a format no type handles, or has no signature where its
     *         extension names a type that has one.
     */
    public static Optional<DocumentType> detect(final byte[] data, final DocumentType byExtension) {
        // Anywhere in the window only for a .pdf file, so a text file that mentions "%PDF-" stays text.
        if (startsWith(data, PDF) || (byExtension == DocumentType.PDF && indexOf(data, PDF, PDF_HEADER_WINDOW) >= 0)) {
            return Optional.of(DocumentType.PDF);
        }
        if (startsWith(data, RTF)) {
            return Optional.of(DocumentType.RTF);
        }
        if (startsWith(data, ZIP)) {
            final OoxmlDocumentType ooxml = OoxmlDocumentType.of(data);
            if (ooxml == null) {
                return Optional.empty();
            }
            return Optional.of(ooxml == OoxmlDocumentType.DOCX ? DocumentType.DOCX : DocumentType.XLSX);
        }
        if (EmailRedactor.isOutlookMessage(data)) {
            return byExtension == DocumentType.EMAIL ? Optional.of(DocumentType.EMAIL) : Optional.empty();
        }
        return switch (byExtension) {
            case TEXT, CSV, EMAIL -> Optional.of(byExtension);
            case PDF, DOCX, XLSX, RTF -> Optional.empty();
        };
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(final byte[] data, final byte[] needle, final int window) {
        final int last = Math.min(data.length, window) - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

}
