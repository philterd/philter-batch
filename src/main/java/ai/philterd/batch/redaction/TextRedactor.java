/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.Formats.Replacement;

import java.util.List;
import java.util.Map;

/** Redacts one text. Implementations are safe to call from several threads. */
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

}
