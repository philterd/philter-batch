/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The input types Philter Batch can redact, each with the Phileas service for it. See docs/configuration.md. */
public enum DocumentType {

    TEXT("text", List.of("txt", "md", "log"), "text/plain"),
    CSV("csv", List.of("csv"), "text/csv"),
    PDF("pdf", List.of("pdf"), "application/pdf"),
    DOCX("docx", List.of("docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("xlsx", List.of("xlsx"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    RTF("rtf", List.of("rtf"), "application/rtf"),
    /** MIME ({@code .eml}) and Outlook ({@code .msg}) messages. Phileas writes both out as MIME. */
    EMAIL("email", List.of("eml", "msg"), "message/rfc822");

    private final String json;
    private final List<String> defaultExtensions;
    private final String contentType;

    DocumentType(final String json, final List<String> defaultExtensions, final String contentType) {
        this.json = json;
        this.defaultExtensions = defaultExtensions;
        this.contentType = contentType;
    }

    /** The name in the run configuration, records, and results. */
    public String json() {
        return json;
    }

    public List<String> defaultExtensions() {
        return defaultExtensions;
    }

    /**
     * The content type of a redacted document of this type. Plain text adds the run's charset. CSV does not: Phileas
     * reads and writes it in the policy's {@code config.csv} encoding, which the run does not know.
     */
    public String contentType(final java.nio.charset.Charset charset) {
        return this == TEXT ? contentType + "; charset=" + charset.name() : contentType;
    }

    /** Whether the input is text in the configured charset: plain text and CSV. */
    public boolean textual() {
        return this == TEXT || this == CSV;
    }

    /**
     * The extension of a redacted document written from an input with {@code extension}: the input's own, or this
     * type's first default when the content is not the type the extension names. Email is always {@code eml}, since
     * Phileas writes Outlook messages as MIME.
     */
    public String outputExtension(final String extension, final boolean mismatch) {
        if (this == EMAIL) {
            return "eml";
        }
        return mismatch ? defaultExtensions.getFirst() : extension;
    }

    public static Optional<DocumentType> of(final String json) {
        if (json == null) {
            return Optional.empty();
        }
        final String name = json.toLowerCase(Locale.ROOT);
        for (final DocumentType type : values()) {
            if (type.json.equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

}
