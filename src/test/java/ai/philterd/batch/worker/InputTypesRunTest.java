/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.Documents;
import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.finalize.Finalizer;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.redaction.PhileasRedactor;
import ai.philterd.batch.run.Formats.ManifestEntry;
import ai.philterd.batch.run.Formats.Report;
import ai.philterd.batch.run.Formats.Section;
import ai.philterd.batch.run.Formats.TextRecord;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.StoredObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole runs over every input type, with Phileas and the regex policy, on invented documents. */
class InputTypesRunTest {

    private static final byte[] POLICY = Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8);
    private static final String ALL_TYPES = "input_types: {handled: [text, csv, pdf, docx, xlsx, rtf, email], "
            + "max_document_bytes: 50000}\nquarantine: {max_percent: 100}\nworker: {threads: 2, onnx_threads: 1}\n";

    private static PhileasRedactor redactor;

    private final TestClock clock = TestClock.at("2026-11-27T12:00:00Z");
    private final InMemoryObjectStore store = new InMemoryObjectStore(clock);

    @BeforeAll
    static void redactor() throws Exception {
        redactor = new PhileasRedactor(LoadedPolicy.load(POLICY, Path.of("/nonexistent")),
                LoadedPolicy.phileasConfiguration(List.of()));
    }

    private void put(final String key, final byte[] data) {
        store.put(Fixtures.INPUT, key, data, "application/octet-stream");
    }

    private void inputs() throws Exception {
        put("in/a.pdf", Documents.pdf());
        put("in/b.docx", Documents.docx());
        put("in/c.xlsx", Documents.xlsx());
        put("in/d.rtf", Documents.rtf());
        put("in/e.eml", Documents.eml());
        put("in/f.csv", Documents.csv());
        put("in/g.txt", (Documents.LINE + "\n").getBytes(StandardCharsets.UTF_8));
        // Content that is not what its extension says.
        put("in/report.txt", Documents.pdf());
        put("in/fake.pdf", "Not a PDF at all.".getBytes(StandardCharsets.UTF_8));
        put("in/broken.rtf", "{\\rtf1 unbalanced".getBytes(StandardCharsets.US_ASCII));
        // Not a handled extension, and over max_document_bytes.
        put("in/old.doc", new byte[] {1, 2, 3});
        final byte[] big = new byte[60_000];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 5);
        put("in/big.pdf", big);
    }

    private void run(final RunConfig config) {
        new Inventory(store, config, clock).run(POLICY);
        assertEquals(ExitCodes.OK, new Worker(store, config, redactor, List.of(), clock, new StopSignal(), "proc")
                .run(false));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
    }

    private String text(final String key) {
        return new String(store.get(Fixtures.OUTPUT, key).orElseThrow().data(), StandardCharsets.UTF_8);
    }

    private Map<String, ManifestEntry> manifestByKey(final RunLayout layout) {
        final Map<String, ManifestEntry> entries = new HashMap<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.manifestPrefix() + "documents-")) {
            for (final String line : text(o.key()).split("\n")) {
                final ManifestEntry e = Json.read(line.getBytes(StandardCharsets.UTF_8), ManifestEntry.class);
                entries.put(e.key(), e);
            }
        }
        return entries;
    }

    /**
     * A document's bytes as searchable text: a Word or Excel file's unzipped parts, a PDF's extracted text, or the
     * bytes themselves.
     */
    private static String readable(final byte[] data) throws Exception {
        if (data.length > 1 && data[0] == 'P' && data[1] == 'K') {
            final StringBuilder out = new StringBuilder();
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertTrue(out.length() > 0);
            return out.toString();
        }
        if (new String(data, 0, Math.min(5, data.length), StandardCharsets.ISO_8859_1).equals("%PDF-")) {
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(data)) {
                return new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            }
        }
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private Report report(final RunLayout layout) {
        return Json.read(store.get(layout.bucket(), layout.report()).orElseThrow().data(), Report.class);
    }

    @Test
    void redactsEveryTypeToTextRecords() throws Exception {
        inputs();
        final RunConfig config = Fixtures.config(ALL_TYPES);
        run(config);
        final RunLayout layout = new RunLayout(config);

        final List<TextRecord> records = new ArrayList<>();
        final StringBuilder all = new StringBuilder();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            final String data = text(part.key());
            all.append(data);
            for (final String line : data.split("\n")) {
                records.add(Json.read(line.getBytes(StandardCharsets.UTF_8), TextRecord.class));
            }
        }
        assertFalse(all.toString().contains(Documents.EMAIL), "no unredacted value reaches the output");
        assertFalse(all.toString().contains("Avery Quill"), "no sheet name reaches the output");
        final Map<String, ManifestEntry> manifest = manifestByKey(layout);
        final Map<String, TextRecord> byKey = records.stream().collect(Collectors.toMap(
                r -> manifest.values().stream().filter(m -> m.documentId().equals(r.documentId())).findFirst()
                        .orElseThrow().key(), r -> r));

        assertEquals(8, records.size());
        assertEquals("pdf", byKey.get("in/a.pdf").type());
        assertEquals("docx", byKey.get("in/b.docx").type());
        assertEquals("xlsx", byKey.get("in/c.xlsx").type());
        assertEquals("rtf", byKey.get("in/d.rtf").type());
        assertEquals("email", byKey.get("in/e.eml").type());
        assertEquals("csv", byKey.get("in/f.csv").type());
        assertEquals("text", byKey.get("in/g.txt").type());
        assertNull(byKey.get("in/g.txt").sections(), "plain text has no sections");
        for (final TextRecord r : records) {
            assertTrue(r.text().contains("{{{REDACTED-email-address}}}"), r.type() + ": " + r.text());
        }

        final TextRecord pdf = byKey.get("in/a.pdf");
        assertEquals(List.of("page 1", "page 2"), pdf.sections().stream().map(Section::label).toList());
        assertEquals(List.of("text", "no_text"), pdf.sections().stream().map(Section::status).toList());
        final Section first = pdf.sections().getFirst();
        assertTrue(pdf.text().substring(first.start(), first.end()).contains("{{{REDACTED-email-address}}}"));
        assertEquals(pdf.sections().get(1).start(), pdf.sections().get(1).end(), "a page with no text is empty");
        assertEquals(List.of("sheet 1"), byKey.get("in/c.xlsx").sections().stream().map(Section::label).toList());

        // A .txt holding a PDF is redacted as a PDF, and the mismatch is recorded.
        assertEquals("pdf", byKey.get("in/report.txt").type());
        assertEquals("pdf", manifest.get("in/report.txt").type());
        assertEquals(Boolean.TRUE, manifest.get("in/report.txt").typeMismatch());
        assertNull(manifest.get("in/a.pdf").typeMismatch());

        assertEquals(ReasonCode.UNSUPPORTED_CONTENT.name(), manifest.get("in/fake.pdf").reason());
        assertEquals(ReasonCode.MALFORMED.name(), manifest.get("in/broken.rtf").reason());
        assertEquals("rtf", manifest.get("in/broken.rtf").type());
        final String skipped = text(layout.manifestSkipped());
        assertTrue(skipped.contains("in/old.doc") && skipped.contains("UNSUPPORTED_TYPE"), skipped);
        assertTrue(skipped.contains("in/big.pdf") && skipped.contains("TOO_LARGE"), skipped);

        final Report report = report(layout);
        assertEquals(Map.of("csv", 1L, "docx", 1L, "email", 1L, "pdf", 2L, "rtf", 1L, "text", 1L, "xlsx", 1L),
                report.entriesByType());
        assertEquals(1L, report.typeMismatches());
        assertEquals(2L, report.sectionsWithoutText());
        assertEquals(2L, report.entriesWithSectionsWithoutText());
        assertEquals(Map.of("MALFORMED", 1L, "UNSUPPORTED_CONTENT", 1L), report.quarantinedByReason());
    }

    @Test
    void redactsEveryTypeToDocumentsOfItsType() throws Exception {
        inputs();
        final RunConfig config = Fixtures.config(ALL_TYPES + "output: {bucket: " + Fixtures.OUTPUT
                + ", prefix: out/, mode: document}\n");
        run(config);
        final RunLayout layout = new RunLayout(config);
        final Map<String, ManifestEntry> manifest = manifestByKey(layout);
        final Map<String, String> expected = Map.of(
                "in/a.pdf", ".pdf", "in/b.docx", ".docx", "in/c.xlsx", ".xlsx", "in/d.rtf", ".rtf",
                "in/e.eml", ".eml", "in/f.csv", ".csv", "in/g.txt", ".txt", "in/report.txt", ".pdf");
        for (final Map.Entry<String, String> e : expected.entrySet()) {
            final String document = manifest.get(e.getKey()).document();
            assertTrue(document != null && document.endsWith(e.getValue()), e.getKey() + " -> " + document);
            final StoredObject stored = store.get(layout.bucket(), document).orElseThrow();
            assertFalse(readable(stored.data()).contains(Documents.EMAIL), document);
        }
        assertEquals("application/pdf", store.contentType(layout.bucket(), manifest.get("in/a.pdf").document()));
        assertEquals("text/csv", store.contentType(layout.bucket(), manifest.get("in/f.csv").document()));
        assertEquals("text/plain; charset=UTF-8", store.contentType(layout.bucket(), manifest.get("in/g.txt").document()));
        assertEquals("message/rfc822", store.contentType(layout.bucket(), manifest.get("in/e.eml").document()));
        assertNull(manifest.get("in/fake.pdf").document());
        assertTrue(store.list(layout.bucket(), layout.partsPrefix()).isEmpty(), "document mode writes no records");
        assertTrue(store.list(layout.bucket(), layout.stagingPrefix()).isEmpty());
    }

    @Test
    void aRunOfPlainTextOnlyIsUnchanged() {
        Fixtures.put(store, "in/a.txt", "Write to " + Documents.EMAIL + ".\n");
        put("in/b.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        final RunConfig config = Fixtures.config("");
        run(config);
        final RunLayout layout = new RunLayout(config);
        final Report report = report(layout);
        assertNull(report.entriesByType());
        assertNull(report.sectionsWithoutText());
        assertTrue(text(layout.manifestSkipped()).contains("in/b.pdf"));
        assertFalse(text(layout.settings()).contains("\"types\""), "run.json is as it was before other types");
    }

    @Test
    void aTypeTheRunDoesNotHandleIsUnsupportedContent() throws Exception {
        // PDF bytes under a .txt name, in a run that handles CSV but not PDF.
        put("in/report.txt", Documents.pdf());
        final RunConfig config = Fixtures.config("input_types: {handled: [text, csv]}\nquarantine: {max_percent: 100}\n");
        run(config);
        final RunLayout layout = new RunLayout(config);
        assertEquals(ReasonCode.UNSUPPORTED_CONTENT.name(), manifestByKey(layout).get("in/report.txt").reason());
    }

    @Test
    void aPlainTextOnlyRunDoesNotDetectTypes() {
        // As before other types: an RTF file named .txt is redacted as text.
        put("in/notes.txt", ("{\\rtf1 Write to " + Documents.EMAIL + "}\n").getBytes(StandardCharsets.US_ASCII));
        final RunConfig config = Fixtures.config("");
        run(config);
        final RunLayout layout = new RunLayout(config);
        final ManifestEntry entry = manifestByKey(layout).get("in/notes.txt");
        assertEquals("redacted", entry.status());
        assertNull(entry.type());
    }

    @Test
    void bothOutputIsRefusedBeyondPlainText() {
        final ConfigException e = assertThrows(ConfigException.class, () -> Fixtures.config(
                "input_types: {handled: [text, pdf]}\noutput: {bucket: " + Fixtures.OUTPUT + ", mode: both}\n"));
        assertTrue(e.getMessage().contains("both"), e.getMessage());
    }

    @Test
    void sectionLabelsAreNumberedInPlaceOfNames() {
        final Map<String, Integer> numbers = new HashMap<>();
        assertEquals("page 1", DocumentProcessor.label("page 1", numbers));
        assertEquals("sheet 1", DocumentProcessor.label("sheet: People", numbers));
        assertEquals("sheet 2", DocumentProcessor.label("sheet: Avery Quill", numbers));
        assertEquals("sheet 1", DocumentProcessor.label("sheet: People", numbers));
        assertEquals("attachment 1 / page 1", DocumentProcessor.label("attachment: Avery Quill.pdf / page 1", numbers));
        assertEquals("attachment 1 / page 2", DocumentProcessor.label("attachment: Avery Quill.pdf / page 2", numbers));
        assertEquals("attachment 2 / sheet 1", DocumentProcessor.label("attachment: people.xlsx / sheet: Staff", numbers));
        assertEquals("attached message 1 / body", DocumentProcessor.label("attached message 1 / body", numbers));
        // A name holding " / " stays one name, and an unknown part is not passed through.
        assertEquals("attachment 3 / page 1", DocumentProcessor.label("attachment: Avery / Quill.pdf / page 1", numbers));
        assertEquals("part", DocumentProcessor.label("Avery Quill", numbers));
        assertEquals("attached message 1 / attachment 1 / text",
                DocumentProcessor.label("attached message 1 / attachment: notes.txt / text", numbers));
    }

    @Test
    void sectionsAreJoinedWithTheirOffsets() {
        final StringBuilder text = new StringBuilder();
        final List<Section> sections = DocumentProcessor.sections(List.of(
                new ai.philterd.batch.redaction.TextRedactor.Section("page 1", "text", "One."),
                new ai.philterd.batch.redaction.TextRedactor.Section("page 2", "no_text", ""),
                new ai.philterd.batch.redaction.TextRedactor.Section("page 3", "text", "Three.")), text);
        assertEquals("One.\n\nThree.", text.toString());
        assertEquals(List.of(new Section("page 1", "text", 0, 4), new Section("page 2", "no_text", 4, 4),
                new Section("page 3", "text", 6, 12)), sections);
        assertArrayEquals(new int[] {0, 4}, new int[] {sections.getFirst().start(), sections.getFirst().end()});
    }

}
