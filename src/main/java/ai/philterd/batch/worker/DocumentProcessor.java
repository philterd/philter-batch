/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.redaction.TextRedactor;
import ai.philterd.batch.run.Formats.DocumentResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Processes one entry, failing closed: the text is decoded, redacted, leftover-checked when that is turned on, and only then written, or
 * the entry is quarantined with a reason code and nothing is written for it. Logs carry IDs and reason codes only.
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
    }

    @Override
    public Outcome process(final int index, final ShardEntry entry) {
        final long started = System.nanoTime();
        final Outcome outcome = processEntry(index, entry);
        final DocumentResult r = outcome.result();
        final long micros = (System.nanoTime() - started) / 1_000;
        return new Outcome(new DocumentResult(r.entry(), r.documentId(), r.segment(), r.segments(), r.status(),
                r.reason(), r.piiCounts(), r.leftoverCounts(), r.leftoverChecked(), r.record(), r.staged(), micros,
                r.replacements()), outcome.record());
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
                    config.runId(), "sha256:" + policySha256));
            record = new byte[json.length + 1];
            System.arraycopy(json, 0, record, 0, json.length);
            record[json.length] = '\n';
        }
        String staged = null;
        if (document != null) {
            staged = layout.staged(entry.documentId(), entry.segment(), entry.segments(), worker, entry.extension());
            store.put(layout.bucket(), staged, document, "text/plain; charset=" + charset.name());
        }
        final DocumentResult done = result(index, entry, DocumentResult.REDACTED, null, redaction.piiCounts(),
                null, redaction.leftoverChecked(), record != null, staged);
        if (!recordReplacements || redaction.replacements() == null) {
            return new Outcome(done, record);
        }
        return new Outcome(new DocumentResult(done.entry(), done.documentId(), done.segment(), done.segments(),
                done.status(), done.reason(), done.piiCounts(), done.leftoverCounts(), done.leftoverChecked(),
                done.record(), done.staged(), null, List.copyOf(redaction.replacements())), record);
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
                pii.isEmpty() ? null : pii, leftover, checked, record, staged, null, null);
    }

    /** A poison entry, quarantined without being read. */
    public static DocumentResult poison(final int index, final ShardEntry entry) {
        LOGGER.warn("Quarantined document {} segment {}: {}", entry.documentId(), entry.segment(), ReasonCode.POISON);
        return result(index, entry, DocumentResult.QUARANTINED, ReasonCode.POISON.name(), Map.of(), null, null, false, null);
    }

}
