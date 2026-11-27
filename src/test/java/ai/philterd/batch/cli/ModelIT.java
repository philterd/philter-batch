/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.Documents;
import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
import ai.philterd.batch.run.Formats.ManifestEntry;
import ai.philterd.batch.run.Formats.Report;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole pipeline with the PhEye model in-process. Set PHILTER_BATCH_TEST_MODELS to a directory holding
 * ph-eye-pii-en-small (the Dockerfile's model stage downloads it at a pinned revision).
 */
@EnabledIfEnvironmentVariable(named = "PHILTER_BATCH_TEST_MODELS", matches = ".+")
class ModelIT {

    @AfterEach
    void restore() {
        Common.storeFactory = c -> ai.philterd.batch.store.ObjectStores.create(c, 8);
    }

    @Test
    void redactsPersonNamesWithTheModel(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        Common.storeFactory = c -> store;
        Fixtures.put(store, "in/memo.txt", "George Washington was the first president. Write to jane.roe@example.com.\n");
        Fixtures.put(store, "in/plain.txt", "The weather was mild and nothing else happened.\n");
        final Path policy = dir.resolve("policy.json");
        Files.writeString(policy, """
                {"identifiers":{
                  "person":{"phEyeConfiguration":{"modelPath":"ph-eye-pii-en-small"},
                    "phEyeFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]},
                  "emailAddress":{"emailAddressFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]}}}
                """);
        final Path config = dir.resolve("run.yaml");
        Files.writeString(config, """
                run_id: model-run
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: out/}
                policy: %s
                models_directory: %s
                worker: {threads: 2, onnx_threads: 2}
                """.formatted(policy, System.getenv("PHILTER_BATCH_TEST_MODELS")));

        // The sample (both texts, since the sample holds up to 50) is redacted first; the rest waits for approval.
        final String c = config.toString();
        assertEquals(ExitCodes.OK, PhilterBatch.execute("inventory", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("sample", "-c", c, "--worker-pods", "4"));
        assertEquals(ExitCodes.ERROR, PhilterBatch.execute("work", "-c", c), "the sample is not approved yet");
        final RunLayout layout = new RunLayout("output-bucket", "out/", "model-run");
        final String html = new String(store.get(layout.bucket(), layout.sampleReportHtml()).orElseThrow().data(),
                StandardCharsets.UTF_8);
        assertFalse(html.contains("George"), "the sample report holds no original value");
        assertFalse(html.contains("Washington"));
        assertFalse(html.contains("jane.roe"));
        assertTrue(html.contains("title=\"email-address\">{{{REDACTED-email-address}}}</mark>"), html);
        assertTrue(html.contains("title=\"person\">{{{REDACTED-name}}}</mark>"), html);
        assertEquals(ExitCodes.OK, PhilterBatch.execute("approve", "-c", c, "sample", "--by", "Test Reviewer"));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("work", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("finalize", "-c", c));
        final StringBuilder out = new StringBuilder();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            out.append(new String(store.get(layout.bucket(), part.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        }
        final String output = out.toString();
        assertFalse(output.contains("George Washington"), output);
        assertFalse(output.contains("jane.roe@example.com"));
        assertTrue(output.contains("{{{REDACTED-email-address}}}"));
        assertTrue(output.contains("The weather was mild"));

        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        // Philter Batch does not run the leftover check for now.
        assertEquals(0, success.counts().leftoverChecked());
        assertEquals(Map.of(), success.counts().quarantinedByReason());
        assertEquals(1, success.models().size());
        assertEquals("ph-eye-pii-en-small", success.models().getFirst().name());
        assertEquals("0ec8b8585ee8e60334efad4206e643b9575d6bf2f9b3e93604183f8aaaf3b40d",
                success.models().getFirst().onnxSha256());
        System.out.println("Model run output: " + output.replace('\n', ' '));
        System.out.println("Model run report: " + new String(store.get(layout.bucket(), layout.report()).orElseThrow()
                .data(), StandardCharsets.UTF_8));
    }

    /** Invented names, each in a sentence the model reads as a person's. */
    private static final List<String> NAMES = List.of("Margaret Ellison", "Thomas Reyes", "Priya Natarajan",
            "Daniel Okafor", "Helen Brandt", "Samuel Whitcombe", "Laura Kowalski");

    private static String line(final String name) {
        return name + " wrote to " + Documents.EMAIL + " about the order.";
    }

    @Test
    void redactsPersonNamesInDocumentsToText(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = documentRun(dir, "text");
        final RunLayout layout = new RunLayout("output-bucket", "out/", "model-docs-text");
        final StringBuilder out = new StringBuilder();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            out.append(new String(store.get(layout.bucket(), part.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        }
        final String output = out.toString();
        for (final String name : NAMES) {
            assertFalse(output.contains(name), name + " in " + output);
            assertFalse(output.contains(name.split(" ")[1]), name + " in " + output);
        }
        assertFalse(output.contains(Documents.EMAIL));
        // The msg's PDF attachment is redacted too, since the policy sets config.email.attachments to redact.
        assertTrue(output.contains("attachment 1 / page 1"), output);
        assertReport(store, layout, 8);
        System.out.println("Model documents output: " + output);
    }

    @Test
    void redactsPersonNamesInDocumentsToDocuments(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = documentRun(dir, "document");
        final RunLayout layout = new RunLayout("output-bucket", "out/", "model-docs-document");
        final List<String> documents = new ArrayList<>();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.manifestPrefix() + "documents-")) {
            for (final String line : new String(store.get(layout.bucket(), o.key()).orElseThrow().data(),
                    StandardCharsets.UTF_8).split("\n")) {
                final ManifestEntry e = Json.read(line.getBytes(StandardCharsets.UTF_8), ManifestEntry.class);
                assertEquals("redacted", e.status(), e.key() + ": " + e.reason());
                documents.add(e.document());
            }
        }
        assertEquals(7, documents.size());
        for (final String document : documents) {
            final String readable = Documents.readable(store.get(layout.bucket(), document).orElseThrow().data());
            for (final String name : NAMES) {
                assertFalse(readable.contains(name), name + " in " + document + ": " + readable);
            }
            assertFalse(readable.contains(Documents.EMAIL), document);
        }
        assertReport(store, layout, 8);
    }

    /** Runs one document of each type, with names in it, through inventory, work, and finalize. */
    private InMemoryObjectStore documentRun(final Path dir, final String mode) throws Exception {
        final InMemoryObjectStore store = new InMemoryObjectStore();
        Common.storeFactory = c -> store;
        final java.util.Map<String, byte[]> inputs = new java.util.LinkedHashMap<>();
        inputs.put("in/a.pdf", Documents.pdf(line(NAMES.get(0))));
        inputs.put("in/b.docx", Documents.docx(line(NAMES.get(1))));
        inputs.put("in/c.xlsx", Documents.xlsx(line(NAMES.get(2))));
        inputs.put("in/d.rtf", Documents.rtf(line(NAMES.get(3))));
        inputs.put("in/e.eml", Documents.eml(line(NAMES.get(4))));
        inputs.put("in/f.msg", Documents.msg(line(NAMES.get(5)), Documents.pdf(line(NAMES.get(6)))));
        inputs.put("in/g.csv", Documents.csv(line(NAMES.get(6))));
        inputs.forEach((key, data) -> store.put("input-bucket", key, data, "application/octet-stream"));
        final Path policy = dir.resolve("policy.json");
        Files.writeString(policy, """
                {"config":{"email":{"attachments":"redact"}},
                 "identifiers":{
                  "person":{"phEyeConfiguration":{"modelPath":"ph-eye-pii-en-small"},
                    "phEyeFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]},
                  "emailAddress":{"emailAddressFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]}}}
                """);
        final Path config = dir.resolve("run.yaml");
        Files.writeString(config, """
                run_id: model-docs-%s
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: out/, mode: %s}
                policy: %s
                models_directory: %s
                input_types: {handled: [text, csv, pdf, docx, xlsx, rtf, email]}
                sample: {enabled: false}
                release_gate: {enabled: false}
                worker: {threads: 2, onnx_threads: 2}
                """.formatted(mode, mode, policy, System.getenv("PHILTER_BATCH_TEST_MODELS")));
        final String c = config.toString();
        assertEquals(ExitCodes.OK, PhilterBatch.execute("inventory", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("work", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("finalize", "-c", c));
        return store;
    }

    private static void assertReport(final InMemoryObjectStore store, final RunLayout layout, final long people) {
        final Report report = Json.read(store.get(layout.bucket(), layout.report()).orElseThrow().data(), Report.class);
        assertEquals(Map.of(), report.quarantinedByReason());
        assertEquals(7L, report.entriesByType().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(report.piiCounts().getOrDefault("person", 0L) >= people - 1,
                "person counts: " + report.piiCounts());
        System.out.println("Model documents report: " + new String(store.get(layout.bucket(), layout.report())
                .orElseThrow().data(), StandardCharsets.UTF_8));
    }

}
