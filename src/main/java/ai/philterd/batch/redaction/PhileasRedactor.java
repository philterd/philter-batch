/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.run.Formats.Replacement;
import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.LeftoverCheckResult;
import ai.philterd.phileas.model.filtering.LeftoverFinding;
import ai.philterd.phileas.model.filtering.ReplacedRange;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextSegment;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.filters.filtering.BinaryFilterService;
import ai.philterd.phileas.services.filters.filtering.CsvFilterService;
import ai.philterd.phileas.services.filters.filtering.EmailFilterService;
import ai.philterd.phileas.services.filters.filtering.FilterOptions;
import ai.philterd.phileas.services.filters.filtering.OoxmlFilterService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.filters.filtering.RtfFilterService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Redacts with Phileas in-process: plain text with a prepared policy, and every other type with the Phileas service
 * for it, each created once and reused, so its filters stay warm. Phileas's leftover check is turned off for now,
 * whatever the policy's {@code config.review.leftoverCheck} says (see docs/configuration.md). Each text or document
 * gets its own context, so {@code CONTEXT}-scoped replacements do not carry from one document to the next.
 */
public final class PhileasRedactor implements TextRedactor {

    private static final String CONTEXT = "document";
    private static final FilterOptions OPTIONS = FilterOptions.defaults();
    private static final FilterOptions TEXT_OUTPUT = FilterOptions.defaults().withOutput(FilterOptions.Output.TEXT);

    private final PlainTextFilterService.PreparedPolicy prepared;
    private final Policy policy;
    private final PhileasConfiguration configuration;
    private final Map<DocumentType, BinaryFilterService> services = new EnumMap<>(DocumentType.class);

    public PhileasRedactor(final LoadedPolicy policy, final PhileasConfiguration configuration) throws Exception {
        if (!policy.ready()) {
            throw new IllegalStateException("The policy needs a key from the pseudonymization secret, and none is set.");
        }
        // The leftover check is off for now: with mode always, Phileas would run it without being asked.
        final var config = policy.policy().getConfig();
        if (config != null && config.getReview() != null && config.getReview().getLeftoverCheck() != null) {
            config.getReview().getLeftoverCheck().setMode(ai.philterd.phileas.policy.config.LeftoverCheck.Mode.OFF);
        }
        // No HTTP client: every PhEye filter runs a local model (LoadedPolicy refuses any other).
        this.prepared = new PlainTextFilterService(configuration, null).prepare(policy.policy());
        this.policy = policy.policy();
        this.configuration = configuration;
    }

    @Override
    public DocumentRedaction redactDocument(final DocumentType type, final byte[] data, final boolean textOutput)
            throws Exception {
        final BinaryDocumentFilterResult result = service(type).filter(policy, new DefaultContextService(),
                new InMemoryVectorService(), CONTEXT, data, mimeType(type), textOutput ? TEXT_OUTPUT : OPTIONS);
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Span span : result.getExplanation().appliedSpans()) {
            counts.merge(span.getFilterType().getType(), 1, Integer::sum);
        }
        final LeftoverCheckResult check = result.getLeftoverCheck();
        final Map<String, Integer> leftover = new TreeMap<>();
        if (check != null && check.isPerformed()) {
            for (final LeftoverFinding finding : check.getFindings()) {
                leftover.merge(finding.getFilterType().getType(), 1, Integer::sum);
            }
        }
        List<Section> sections = null;
        if (textOutput) {
            sections = new ArrayList<>();
            for (final TextSegment segment : result.getText().getSegments()) {
                sections.add(new Section(segment.getLabel(), segment.getStatus().name().toLowerCase(Locale.ROOT),
                        segment.getText() == null ? "" : segment.getText()));
            }
        }
        return new DocumentRedaction(sections, textOutput ? null : result.getDocument(), counts,
                check != null && check.isPerformed(), leftover);
    }

    /** The service for a type, created on first use. No HTTP client: every PhEye filter runs a local model. */
    private synchronized BinaryFilterService service(final DocumentType type) {
        return services.computeIfAbsent(type, t -> switch (t) {
            case PDF -> new PdfFilterService(configuration, null);
            case DOCX, XLSX -> new OoxmlFilterService(configuration, null);
            case RTF -> new RtfFilterService(configuration, null);
            case EMAIL -> new EmailFilterService(configuration, null);
            case CSV -> new CsvFilterService(configuration, null);
            case TEXT -> throw new IllegalArgumentException("Plain text is redacted with redact(String).");
        });
    }

    /** The output type each service is asked for: the input's own, or MIME for email (Phileas writes .msg as MIME). */
    static MimeType mimeType(final DocumentType type) {
        return switch (type) {
            case PDF -> MimeType.APPLICATION_PDF;
            case DOCX -> MimeType.APPLICATION_DOCX;
            case XLSX -> MimeType.APPLICATION_XLSX;
            case RTF -> MimeType.APPLICATION_RTF;
            case EMAIL -> MimeType.MESSAGE_RFC822;
            case CSV -> MimeType.TEXT_CSV;
            case TEXT -> MimeType.TEXT_PLAIN;
        };
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
