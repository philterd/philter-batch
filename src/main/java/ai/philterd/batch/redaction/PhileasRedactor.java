/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.LeftoverCheckResult;
import ai.philterd.batch.run.Formats.Replacement;
import ai.philterd.phileas.model.filtering.LeftoverFinding;
import ai.philterd.phileas.model.filtering.ReplacedRange;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.filters.filtering.FilterOptions;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Redacts with Phileas in-process. Phileas's leftover check is turned off for now, whatever the policy's
 * {@code config.review.leftoverCheck} says (see docs/configuration.md). Each text gets its own context, so a value's
 * replacement is consistent within one document (or segment) and not across documents.
 */
public final class PhileasRedactor implements TextRedactor {

    private static final String CONTEXT = "document";
    private static final FilterOptions OPTIONS = FilterOptions.defaults();

    private final PlainTextFilterService.PreparedPolicy prepared;

    public PhileasRedactor(final LoadedPolicy policy, final PhileasConfiguration configuration) throws Exception {
        // The leftover check is off for now: with mode always, Phileas would run it without being asked.
        final var config = policy.policy().getConfig();
        if (config != null && config.getReview() != null && config.getReview().getLeftoverCheck() != null) {
            config.getReview().getLeftoverCheck().setMode(ai.philterd.phileas.policy.config.LeftoverCheck.Mode.OFF);
        }
        // No HTTP client: every PhEye filter runs a local model (LoadedPolicy refuses any other).
        this.prepared = new PlainTextFilterService(configuration, null).prepare(policy.policy());
    }

    @Override
    public Redaction redact(final String text) throws Exception {
        final TextFilterResult result = prepared.filter(new DefaultContextService(), new InMemoryVectorService(),
                CONTEXT, text, OPTIONS);
        final List<Span> applied = result.getExplanation().appliedSpans();
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Span span : applied) {
            counts.merge(span.getFilterType().getType(), 1, Integer::sum);
        }
        final LeftoverCheckResult check = result.getLeftoverCheck();
        final Map<String, Integer> leftover = new TreeMap<>();
        if (check.isPerformed()) {
            for (final LeftoverFinding finding : check.getFindings()) {
                leftover.merge(finding.getFilterType().getType(), 1, Integer::sum);
            }
        }
        return new Redaction(result.getFilteredText(), counts, check.isPerformed(), leftover,
                replacements(result.getFilteredText(), applied, result.getReplacedRanges()));
    }

    /**
     * Pairs Phileas's replaced ranges (where each replacement is in the redacted text) with the applied spans' types.
     * Phileas builds the ranges from the applied spans in order of their start, so the i-th range is the i-th span;
     * each pair is checked against the span's replacement, and null is returned if any does not match, rather than
     * guessing. Only the replacement is compared, never the span's original text.
     */
    static List<Replacement> replacements(final String filtered, final List<Span> applied,
                                          final List<ReplacedRange> ranges) {
        if (ranges == null || ranges.size() != applied.size()) {
            return null;
        }
        final List<Span> ordered = new ArrayList<>(applied);
        ordered.sort(Comparator.comparingInt(Span::getCharacterStart));
        final List<Replacement> out = new ArrayList<>(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            final ReplacedRange range = ranges.get(i);
            final Span span = ordered.get(i);
            if (range.start() < 0 || range.end() > filtered.length() || range.start() > range.end()
                    || !filtered.substring(range.start(), range.end()).equals(span.getReplacement())) {
                return null;
            }
            out.add(new Replacement(range.start(), range.end(), span.getFilterType().getType()));
        }
        return out;
    }

}
