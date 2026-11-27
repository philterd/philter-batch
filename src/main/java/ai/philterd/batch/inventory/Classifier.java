/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.DocumentType;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * Decides which input objects a run redacts, and as which type, by extension. Inventory does not read the files: a
 * worker detects each file's type from its content when it redacts it (see DocumentTypes), since a Word document
 * or Excel workbook can only be told apart from the whole file.
 */
public final class Classifier {

    /** Reported in place of an extension that is not 1 to 10 letters or digits, since extensions can hold text. */
    public static final String OTHER = "(other)";
    public static final String NONE = "(none)";

    private final Map<String, DocumentType> types = new HashMap<>();

    public Classifier(final RunConfig config) {
        for (final DocumentType type : config.inputTypes().types()) {
            config.extensions(type).forEach(extension -> types.put(extension, type));
        }
    }

    /** The handled type an extension names, if any. */
    public Optional<DocumentType> type(final String extension) {
        return Optional.ofNullable(types.get(extension));
    }

    /** The key's extension, lowercased, or {@link #NONE} or {@link #OTHER}. */
    public static String extension(final String key) {
        final String name = key.substring(key.lastIndexOf('/') + 1);
        final int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return NONE;
        }
        final String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ConfigLoader.EXTENSION.matcher(extension).matches() ? extension : OTHER;
    }

    public boolean isHandled(final String extension) {
        return types.containsKey(extension);
    }

    /** A key ending in a slash: a folder placeholder some tools create, not a file. */
    public static boolean isDirectoryMarker(final String key) {
        return key.endsWith("/");
    }

    /**
     * Whether files in the charset can be split at newline bytes: a newline is the single byte 0x0A and ASCII
     * letters are single bytes, so a 0x0A byte is always a line break (true of UTF-8 and the ISO-8859 and
     * Windows code pages; not of UTF-16 or UTF-32).
     */
    public static boolean splittable(final Charset charset) {
        try {
            return Arrays.equals("\n".getBytes(charset), new byte[] {0x0A})
                    && Arrays.equals("Az".getBytes(charset), new byte[] {0x41, 0x7A});
        } catch (final UnsupportedOperationException e) {
            return false;
        }
    }

}
