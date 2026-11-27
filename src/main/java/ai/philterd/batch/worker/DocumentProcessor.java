/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.inventory.Classifier;
import ai.philterd.batch.redaction.DocumentTypes;
import ai.philterd.batch.redaction.TextRedactor;
import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.Section;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Formats.TextRecord;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectNotFoundException;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.PreconditionFailedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Processes one entry, failing closed: the text is decoded, redacted, leftover-checked when that is turned on, and only then written, or
 * the entry is quarantined with a reason code and nothing is written for it. Logs carry IDs and reason codes only.
 *
 * <p>In a run that handles more than plain text, a whole file's type is detected from its content
 * ({@link DocumentTypes}) and redacted as that type, which can differ from the type its extension names. A segment
 * of a split plain-text file is plain text, as is every file of a plain-text-only run.</p>
 *
 * <p>Errors reading or writing the store are not quarantine reasons: they propagate, and the shard is retried by
 * another lease, so an outage does not quarantine good documents.</p>
 */
public final class DocumentProcessor implements EntryProcessor {

    private static final Logger LOGGER = LogManager.getLogger(DocumentProcessor.class);

    /** The {@code schema_version} of the JSONL records this build writes. */
    public static final int RECORD_SCHEMA_VERSION = 1;

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final TextRedactor redactor;
    private final String policySha256;
    private final String worker;
    private final ExecutorService redactions;
    private final Charset charset;
    private final boolean recordReplacements;
    private final Classifier classifier;
    private final Set<DocumentType> handled;
    private final boolean detect;

    /**
     * One processed entry.
     * @param result Its result for the progress and done marker.
     * @param record Its JSONL line, with the newline, or null.
     */
    public record Outcome(DocumentResult result, byte[] record) {
    }

    public DocumentProcessor(final ObjectStore store, final RunConfig config, final RunLayout layout,
                             final TextRedactor redactor, final String policySha256, final String worker,
                             final ExecutorService redactions) {
        this(store, config, layout, redactor, policySha256, worker, redactions, false);
    }

    /**
     * @param recordReplacements Record where the replacements are in each result, as the sample does for its
     *                           report.
     */
    public DocumentProcessor(final ObjectStore store, final RunConfig config, final RunLayout layout,
                             final TextRedactor redactor, final String policySha256, final String worker,
                             final ExecutorService redactions, final boolean recordReplacements) {
        this.recordReplacements = recordReplacements;
        this.store = store;
        this.config = config;
        this.layout = layout;
        this.redactor = redactor;
        this.policySha256 = policySha256;
        this.worker = worker;
        this.redactions = redactions;
        this.charset = config.plainText().charsetValue();
        this.classifier = new Classifier(config);
        this.handled = config.inputTypes().types();
        this.detect = !handled.equals(java.util.EnumSet.of(DocumentType.TEXT));
    }

    @Override
    public Outcome process(final int index, final ShardEntry entry) {
        final long started = System.nanoTime();
        final Outcome outcome = processEntry(index, entry);
        final long micros = (System.nanoTime() - started) / 1_000;
        return new Outcome(outcome.result().withMicros(micros), outcome.record());
    }

    private Outcome processEntry(final int index, final ShardEntry entry) {
        final byte[] bytes;
        try {
            bytes = entry.whole()
                    ? store.getIfMatch(config.input().bucket(), entry.key(), entry.etag()).data()
                    : SegmentReader.read(store, config.input().bucket(), entry, config.inventory().maxLineBytes());
        } catch (final PreconditionFailedException e) {
            return quarantine(index, entry, ReasonCode.CHANGED_SINCE_INVENTORY);
        } catch (final ObjectNotFoundException e) {
            return quarantine(index, entry, ReasonCode.INPUT_MISSING);
        } catch (final LineTooLongException e) {
            return quarantine(index, entry, ReasonCode.LINE_TOO_LONG);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length == 0) {
            return new Outcome(result(index, entry, DocumentResult.EMPTY, null, Map.of(), null, null, false, null), null);
        }

        // Inventory chose the entry by its extension, so the extension names a handled type.
        final DocumentType byExtension = classifier.type(entry.extension()).orElse(DocumentType.TEXT);
        DocumentType type = byExtension;
        // A plain-text-only run reads every file as text, as it did before other types.
        if (entry.whole() && detect) {
            final Optional<DocumentType> detected = DocumentTypes.detect(bytes, byExtension);
            if (detected.isEmpty() || !handled.contains(detected.get())) {
                return quarantine(index, entry, ReasonCode.UNSUPPORTED_CONTENT);
            }
            type = detected.get();
        }
        final boolean mismatch = type != byExtension;
        if (mismatch) {
            LOGGER.info("Document {} is {} by its content, not {} as its extension says.", entry.documentId(),
                    type.json(), byExtension.json());
        }
        if (type != DocumentType.TEXT) {
            return processDocument(index, entry, type, mismatch, bytes);
        }

        final String text;
        try {
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException e) {
            return quarantine(index, entry, ReasonCode.UNDECODABLE);
        }

        final TextRedactor.Redaction redaction;
        final Future<TextRedactor.Redaction> future = redactions.submit(() -> redactor.redact(text));
        try {
            redaction = future.get(config.worker().fileTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            return quarantine(index, entry, ReasonCode.TIMEOUT);
        } catch (final ExecutionException e) {
            // The class only: a Phileas exception message could quote the text.
            LOGGER.warn("Redaction of document {} segment {} failed: {}", entry.documentId(), entry.segment(),
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getName());
            return quarantine(index, entry, ReasonCode.REDACTION_FAILED);
        } catch (final InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while redacting.", e);
        }

        if (!redaction.leftoverCounts().isEmpty()) {
            return quarantine(index, entry, ReasonCode.LEFTOVER_FOUND, redaction.leftoverCounts(), true);
        }

        // Everything that can fail is done before anything is written.
        byte[] document = null;
        if (config.output().mode().document()) {
            try {
                final ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(redaction.text()));
                document = new byte[encoded.remaining()];
                encoded.get(document);
            } catch (final CharacterCodingException e) {
                return quarantine(index, entry, ReasonCode.ENCODING_FAILED);
            }
        }
        byte[] record = null;
        if (config.output().mode().text()) {
            final String id = entry.whole() ? entry.documentId()
                    : entry.documentId() + "." + String.format("%05d", entry.segment());
            final byte[] json = Json.write(new TextRecord(RECORD_SCHEMA_VERSION, id, entry.documentId(),
                    entry.segment(), entry.segments(), "text", redaction.text(), redaction.piiCounts(),
                    config.runId(), "sha256:" + policySha256, null));
            record = new byte[json.length + 1];
            System.arraycopy(json, 0, record, 0, json.length);
            record[json.length] = '\n';
        }
        String staged = null;
        if (document != null) {
            staged = layout.staged(entry.documentId(), entry.segment(), entry.segments(), worker, entry.extension());
            store.put(layout.bucket(), staged, document, "text/plain; charset=" + charset.name());
        }
        DocumentResult done = result(index, entry, DocumentResult.REDACTED, null, redaction.piiCounts(),
                null, redaction.leftoverChecked(), record != null, staged);
        if (mismatch) {
            done = typed(done, DocumentType.TEXT, true, null);
        }
        if (!recordReplacements || redaction.replacements() == null) {
            return new Outcome(done, record);
        }
        return new Outcome(done.withReplacements(List.copyOf(redaction.replacements())), record);
    }

    /** A whole file of a type other than plain text, redacted with the Phileas service for its type. */
    private Outcome processDocument(final int index, final ShardEntry entry, final DocumentType type,
                                    final boolean mismatch, final byte[] bytes) {
        final boolean textOutput = config.output().mode().text();
        final TextRedactor.DocumentRedaction redaction;
        try {
            redaction = await(() -> redactor.redactDocument(type, bytes, textOutput));
        } catch (final TimeoutException e) {
            return typedQuarantine(index, entry, ReasonCode.TIMEOUT, type, mismatch);
        } catch (final ExecutionException e) {
            final ReasonCode reason = ReasonCode.forFailure(e.getCause());
            LOGGER.warn("Redaction of document {} ({}) failed: {}", entry.documentId(), type.json(),
                    e.getCause() == null ? "unknown" : e.getCause().getClass().getName());
            return typedQuarantine(index, entry, reason, type, mismatch);
        }
        if (!redaction.leftoverCounts().isEmpty()) {
            final Outcome q = quarantine(index, entry, ReasonCode.LEFTOVER_FOUND, redaction.leftoverCounts(), true);
            return new Outcome(typed(q.result(), type, mismatch, null), null);
        }

        byte[] record = null;
        Integer withoutText = null;
        if (textOutput) {
            final StringBuilder text = new StringBuilder();
            final List<Section> sections = sections(redaction.sections(), text);
            final long empty = redaction.sections().stream().filter(x -> "no_text".equals(x.status())).count();
            withoutText = empty == 0 ? null : (int) empty;
            final byte[] json = Json.write(new TextRecord(RECORD_SCHEMA_VERSION, entry.documentId(), entry.documentId(),
                    0, 1, type.json(), text.toString(), redaction.piiCounts(), config.runId(), "sha256:" + policySha256,
                    sections));
            record = new byte[json.length + 1];
            System.arraycopy(json, 0, record, 0, json.length);
            record[json.length] = '\n';
        }
        String staged = null;
        if (config.output().mode().document()) {
            if (redaction.document() == null) {
                return typedQuarantine(index, entry, ReasonCode.REDACTION_FAILED, type, mismatch);
            }
            staged = layout.staged(entry.documentId(), entry.segment(), entry.segments(), worker,
                    type.outputExtension(entry.extension(), mismatch));
            store.put(layout.bucket(), staged, redaction.document(), type.contentType(charset));
        }
        return new Outcome(typed(result(index, entry, DocumentResult.REDACTED, null, redaction.piiCounts(), null,
                redaction.leftoverChecked(), record != null, staged), type, mismatch, withoutText), record);
    }

    /**
     * Joins the sections' text into {@code text}, separated by a blank line, and returns where each one is. A section
     * with no text has {@code start == end}. Labels are numbered in place of the sheet and attachment names they hold,
     * which are not redacted ({@link #label}).
     */
    static List<Section> sections(final List<TextRedactor.Section> sections, final StringBuilder text) {
        final List<Section> out = new ArrayList<>(sections.size());
        final Map<String, Integer> numbers = new HashMap<>();
        for (final TextRedactor.Section section : sections) {
            if (!section.text().isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
            }
            final int start = text.length();
            text.append(section.text());
            out.add(new Section(label(section.label(), numbers), section.status(), start, text.length()));
        }
        return out;
    }

    /** The parts of Phileas's labels that never hold a name from the document. */
    private static final java.util.regex.Pattern KNOWN_PART = java.util.regex.Pattern.compile(
            "page \\d+|body|headers|footers|headers and footers|footnotes|endnotes|comments|tracked changes|rows|text"
                    + "|metadata|attached message \\d+");
    /** The parts that are a kind and a name, such as {@code sheet: People}. */
    private static final java.util.regex.Pattern NAMED_PART = java.util.regex.Pattern.compile("(sheet|attachment): .*");

    /**
     * A label without the names Phileas puts in it. A part of the form {@code kind: name}, such as
     * {@code sheet: People} or {@code attachment: report.pdf}, becomes {@code kind N}, numbered in the order the names
     * first appear under the same parent, so {@code attachment: report.pdf / sheet: People} becomes
     * {@code attachment 1 / sheet 1}. Known parts without a name, such as {@code page 2} or {@code body}, are kept.
     * Anything else becomes {@code part}, so a name that holds {@code " / "} or a label this version does not know
     * cannot reach a record.
     */
    static String label(final String label, final Map<String, Integer> numbers) {
        final StringBuilder out = new StringBuilder();
        final String[] parts = label.split(" / ", -1);
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i];
            final String parent = out.toString();
            if (!out.isEmpty()) {
                out.append(" / ");
            }
            if (KNOWN_PART.matcher(part).matches()) {
                out.append(part);
                continue;
            }
            if (!NAMED_PART.matcher(part).matches()) {
                out.append("part");
                continue;
            }
            final int colon = part.indexOf(": ");
            final String kind = part.substring(0, colon);
            // An attachment's name runs to the last part a nested label could start with, so a name that holds
            // " / " is one name, not a name and a part.
            String name = part.substring(colon + 2);
            while (kind.equals("attachment") && i + 1 < parts.length && !KNOWN_PART.matcher(parts[i + 1]).matches()
                    && !NAMED_PART.matcher(parts[i + 1]).matches()) {
                name = name + " / " + parts[++i];
            }
            final String key = parent + "\u0000" + kind + "\u0000" + name;
            final String counter = parent + "\u0000" + kind;
            Integer n = numbers.get(key);
            if (n == null) {
                n = numbers.merge(counter, 1, Integer::sum);
                numbers.put(key, n);
            }
            out.append(kind).append(' ').append(n);
        }
        return out.toString();
    }

    /** Runs a redaction on the redaction pool, within the file timeout. */
    private <T> T await(final Callable<T> redaction) throws TimeoutException, ExecutionException {
        final Future<T> future = redactions.submit(redaction);
        try {
            return future.get(config.worker().fileTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (final InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while redacting.", e);
        }
    }

    private Outcome typedQuarantine(final int index, final ShardEntry entry, final ReasonCode reason,
                                    final DocumentType type, final boolean mismatch) {
        return new Outcome(typed(quarantine(index, entry, reason).result(), type, mismatch, null), null);
    }

    /** A result with its type, recorded only for a type other than plain text or a mismatch. */
    private static DocumentResult typed(final DocumentResult r, final DocumentType type, final boolean mismatch,
                                        final Integer withoutText) {
        return new DocumentResult(r.entry(), r.documentId(), r.segment(), r.segments(), r.status(), r.reason(),
                r.piiCounts(), r.leftoverCounts(), r.leftoverChecked(), r.record(), r.staged(), r.micros(),
                r.replacements(), type.json(), mismatch ? Boolean.TRUE : null, withoutText);
    }

    private Outcome quarantine(final int index, final ShardEntry entry, final ReasonCode reason) {
        return quarantine(index, entry, reason, null, null);
    }

    private Outcome quarantine(final int index, final ShardEntry entry, final ReasonCode reason,
                               final Map<String, Integer> leftover, final Boolean checked) {
        LOGGER.info("Quarantined document {} segment {}: {}", entry.documentId(), entry.segment(), reason);
        return new Outcome(result(index, entry, DocumentResult.QUARANTINED, reason.name(), Map.of(), leftover, checked,
                false, null), null);
    }

    private static DocumentResult result(final int index, final ShardEntry entry, final String status,
                                         final String reason, final Map<String, Integer> pii,
                                         final Map<String, Integer> leftover, final Boolean checked,
                                         final boolean record, final String staged) {
        return new DocumentResult(index, entry.documentId(), entry.segment(), entry.segments(), status, reason,
                pii.isEmpty() ? null : pii, leftover, checked, record, staged, null, null, null, null, null);
    }

    /** A poison entry, quarantined without being read. */
    public static DocumentResult poison(final int index, final ShardEntry entry) {
        LOGGER.warn("Quarantined document {} segment {}: {}", entry.documentId(), entry.segment(), ReasonCode.POISON);
        return result(index, entry, DocumentResult.QUARANTINED, ReasonCode.POISON.name(), Map.of(), null, null, false, null);
    }

}
