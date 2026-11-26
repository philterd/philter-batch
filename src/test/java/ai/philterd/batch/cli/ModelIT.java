/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
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

}
