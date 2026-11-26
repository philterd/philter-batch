/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.inventory;

import ai.philterd.batch.config.ConfigLoader;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decides which input objects are plain text, by extension. Provisional: whether to use extensions,
 * {@code tika-core}'s detection, or both is an open decision on the design page.
 */
public final class Classifier {

    /** Reported in place of an extension that is not 1 to 10 letters or digits, since extensions can hold text. */
    public static final String OTHER = "(other)";
    public static final String NONE = "(none)";

    private final List<String> extensions;

    public Classifier(final List<String> extensions) {
        this.extensions = List.copyOf(extensions);
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

    public boolean isPlainText(final String extension) {
        return extensions.contains(extension);
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
