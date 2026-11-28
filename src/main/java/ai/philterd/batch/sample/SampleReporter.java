/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.sample;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.DocumentType;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.redaction.PolicyStrategies;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.PolicyWarning;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.Estimate;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.SampleEntry;
import ai.philterd.batch.run.Formats.SampleReport;
import ai.philterd.batch.run.Formats.Shard;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.Formats.TextRecord;
import ai.philterd.batch.run.Formats.Throughput;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.StoredObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Writes the sample's output and reports once every sample shard is done: under {@code sample/}, the sample's
 * redacted records ({@code records.jsonl}) or documents ({@code documents/}), the HTML report, and last
 * {@code report.json}, whose presence marks the sample complete. Everything is built from the done markers and the
 * redacted output, so it can be rebuilt after a crash. Nothing here reads the input.
 */
public final class SampleReporter {

    private static final Logger LOGGER = LogManager.getLogger(SampleReporter.class);

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final Clock clock;

    public SampleReporter(final ObjectStore store, final RunConfig config, final Clock clock) {
        this.store = store;
        this.config = config;
        this.layout = new RunLayout(config);
        this.clock = clock;
    }

    /** The sample's shard IDs: the first {@code sample_shards} shards. */
    public static List<String> sampleShards(final InventorySummary inventory) {
        final List<String> ids = new ArrayList<>(inventory.sampleShards());
        for (int i = 0; i < inventory.sampleShards(); i++) {
            ids.add(RunLayout.shardId(i));
        }
        return ids;
    }

    /** The existing report, if the sample is complete. */
    public Optional<SampleReport> existing() {
        return store.get(layout.bucket(), layout.sampleReport()).map(o -> {
            final SampleReport report = Json.read(o.data(), SampleReport.class);
            FormatException.check("sample/report.json", report.format(), Formats.FORMAT);
            return report;
        });
    }

    /**
     * Writes the sample's output and reports.
     * @param threads Shards processed at once in one worker pod, for the throughput per pod.
     * @param workerPods The worker pods of the full run, for the estimate.
     * @return {@link ExitCodes#OK}, or {@link ExitCodes#INCOMPLETE} if a sample shard has no done marker.
     */
    public int write(final InventorySummary inventory, final int threads, final int workerPods) {
        if (existing().isPresent()) {
            LOGGER.info("The sample report of run {} already exists.", config.runId());
            return ExitCodes.OK;
        }
        final Charset charset = config.plainText().charsetValue();
        final List<Sampled> sampled = new ArrayList<>();
        final ByteArrayOutputStream records = new ByteArrayOutputStream();
        DoneMarker first = null;
        for (final String id : sampleShards(inventory)) {
            final Optional<StoredObject> done = store.get(layout.bucket(), layout.done(id));
            if (done.isEmpty()) {
                LOGGER.warn("Sample shard {} of run {} has no done marker.", id, config.runId());
                return ExitCodes.INCOMPLETE;
            }
            final DoneMarker marker = Json.read(done.get().data(), DoneMarker.class);
            FormatException.check("Done marker " + id, marker.format(), Formats.FORMAT);
            first = first == null ? marker : first;
            final Shard shard = Json.read(store.get(layout.bucket(), layout.shard(id)).orElseThrow().data(), Shard.class);
            final Map<String, String> texts = new HashMap<>();
            for (final String part : marker.parts()) {
                final byte[] data = store.get(layout.bucket(), part).orElseThrow(
                        () -> new FormatException("Part " + part + " named by done marker " + id + " is missing.")).data();
                records.writeBytes(data);
                for (final String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isEmpty()) {
                        final TextRecord record = Json.read(line.getBytes(StandardCharsets.UTF_8), TextRecord.class);
                        texts.put(record.id(), record.text());
                    }
                }
            }
            for (final DocumentResult result : marker.results()) {
                final ShardEntry entry = shard.entries().get(result.entry());
                String text = texts.get(recordId(entry));
                String output = null;
                if (config.output().mode().document() && result.staged() != null) {
                    final DocumentType type = DocumentType.of(result.type()).orElse(DocumentType.TEXT);
                    final byte[] document = staged(result, entry, type);
                    if (document != null) {
                        output = layout.sampleDocument(entry.documentId(), entry.segment(), entry.segments(),
                                type.outputExtension(entry.extension(), Boolean.TRUE.equals(result.typeMismatch())));
                        store.put(layout.bucket(), output, document, type.contentType(charset));
                        output = output.substring(layout.root().length());
                        // Only plain text and CSV can be shown as they are; other documents are linked.
                        if (text == null && type.textual()) {
                            text = new String(document, charset);
                        }
                    }
                }
                sampled.add(new Sampled(entry, result, text, output));
            }
        }

        if (records.size() > 0) {
            store.put(layout.bucket(), layout.sampleRecords(), records.toByteArray(), "application/x-ndjson");
        }

        final SampleReport report = report(inventory, sampled, first, threads, workerPods);
        final Map<String, HtmlSampleReport.Text> texts = new HashMap<>();
        for (final Sampled s : sampled) {
            if (s.text() != null) {
                texts.put(recordId(s.entry()), new HtmlSampleReport.Text(s.text(), s.result().replacements()));
            }
        }
        store.put(layout.bucket(), layout.sampleReportHtml(),
                HtmlSampleReport.render(report, texts, config.sample().reportMaxChars()).getBytes(StandardCharsets.UTF_8),
                "text/html; charset=UTF-8");
        store.put(layout.bucket(), layout.sampleReport(), Json.write(report), "application/json");
        LOGGER.info("Wrote the sample report of run {}: {} entries {}; quarantined {}.", config.runId(),
                report.entries(), report.entriesByStatus(), report.quarantinedByReason());
        return ExitCodes.OK;
    }

    /** One sampled entry, with its redacted text when it has one, and its sample document's key. */
    private record Sampled(ShardEntry entry, DocumentResult result, String text, String output) {
    }

    /** The JSONL record ID of an entry, as the worker writes it. */
    static String recordId(final ShardEntry entry) {
        return entry.whole() ? entry.documentId() : entry.documentId() + "." + String.format("%05d", entry.segment());
    }

    /** A sampled entry's redacted document: staged, or already put in place if finalize has run. */
    private byte[] staged(final DocumentResult result, final ShardEntry entry, final DocumentType type) {
        final Optional<StoredObject> staged = store.get(layout.bucket(), result.staged());
        if (staged.isPresent()) {
            return staged.get().data();
        }
        if (entry.whole()) {
            return store.get(layout.bucket(), layout.document(entry.documentId(),
                    type.outputExtension(entry.extension(), Boolean.TRUE.equals(result.typeMismatch()))))
                    .map(StoredObject::data).orElse(null);
        }
        return null;
    }

    private SampleReport report(final InventorySummary inventory, final List<Sampled> sampled, final DoneMarker first,
                                final int threads, final int workerPods) {
        final Map<String, Long> byExtension = new TreeMap<>();
        final Map<String, Long> byStatus = new TreeMap<>();
        final Map<String, Long> byReason = new TreeMap<>();
        final Map<String, Long> pii = new TreeMap<>();
        final Map<String, Long> leftover = new TreeMap<>();
        final List<SampleEntry> entries = new ArrayList<>();
        final java.util.Set<String> documents = new java.util.HashSet<>();
        long bytes = 0;
        long timedBytes = 0;
        long micros = 0;
        long checked = 0;
        for (final Sampled s : sampled) {
            final ShardEntry e = s.entry();
            final DocumentResult r = s.result();
            final long size = e.end() - e.start();
            documents.add(e.documentId());
            bytes += size;
            byExtension.merge(e.extension(), 1L, Long::sum);
            byStatus.merge(r.status(), 1L, Long::sum);
            if (r.reason() != null) {
                byReason.merge(r.reason(), 1L, Long::sum);
            }
            if (r.piiCounts() != null) {
                r.piiCounts().forEach((t, n) -> pii.merge(t, (long) n, Long::sum));
            }
            if (r.leftoverCounts() != null) {
                r.leftoverCounts().forEach((t, n) -> leftover.merge(t, (long) n, Long::sum));
            }
            if (Boolean.TRUE.equals(r.leftoverChecked())) {
                checked++;
            }
            if (r.micros() != null) {
                timedBytes += size;
                micros += r.micros();
            }
            entries.add(new SampleEntry(recordId(e), e.documentId(), e.segment(), e.segments(), e.extension(), size,
                    r.status(), r.reason(), r.piiCounts(), r.leftoverCounts(), r.leftoverChecked(), r.micros(),
                    s.output()));
        }
        Throughput throughput = null;
        Estimate estimate = null;
        if (micros > 0 && timedBytes > 0) {
            final double seconds = micros / 1_000_000.0;
            final double perThread = timedBytes / seconds;
            final double perPod = perThread * threads;
            throughput = new Throughput(timedBytes, seconds, perThread, threads, perPod);
            final long remainingBytes = Math.max(0, inventory.bytes() - inventory.sampleBytes());
            final long remainingEntries = Math.max(0, inventory.entries() - inventory.sampleEntries());
            final double hours = remainingBytes / (perPod * workerPods) / 3600.0;
            final double workerHours = hours * workerPods;
            final Double price = config.sample().pricePerWorkerHour();
            estimate = new Estimate(remainingBytes, remainingEntries, workerPods, hours, workerHours, price,
                    price == null ? null : config.sample().currency(), price == null ? null : workerHours * price);
        }
        return new SampleReport(Formats.FORMAT, config.runId(), clock.instant(),
                first == null ? null : first.policySha256(), first == null ? null : first.phileasVersion(),
                first == null ? null : first.pheyeOnnxVersion(), first == null ? List.of() : first.models(),
                config.output().mode().json(), Inventory.sampleSeed(config), sampled.size(), documents.size(), bytes,
                byExtension, byStatus, byReason, pii, leftover, checked, throughput, estimate, entries,
                policyWarnings());
    }

    /** The policy's strategies a reviewer should know about, from the policy stored with the run. */
    private List<PolicyWarning> policyWarnings() {
        return store.get(layout.bucket(), layout.policy()).map(o -> {
            final JsonElement policy = JsonParser.parseString(new String(o.data(), StandardCharsets.UTF_8));
            return policy.isJsonObject() ? PolicyStrategies.of(policy.getAsJsonObject()).warnings()
                    : List.<PolicyWarning>of();
        }).orElse(List.of());
    }

}
