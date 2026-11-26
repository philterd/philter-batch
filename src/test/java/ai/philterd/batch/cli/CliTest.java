/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.PutResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    private final Function<RunConfig.Store, ObjectStore> original = Common.storeFactory;

    @AfterEach
    void restore() {
        Common.storeFactory = original;
    }

    /** An in-memory store that survives the command closing it, so the test can read the output. */
    private static InMemoryObjectStore use(final InMemoryObjectStore store) {
        Common.storeFactory = c -> store;
        return store;
    }

    private static Path config(final Path dir, final String extra) throws Exception {
        final Path policy = dir.resolve("policy.json");
        Files.writeString(policy, Fixtures.REGEX_POLICY);
        final Path config = dir.resolve("run.yaml");
        Files.writeString(config, """
                run_id: cli-run
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: out/}
                policy: %s
                lease: {initial_seconds: 60, renew_seconds: 10}
                worker: {threads: 2, onnx_threads: 1}
                sample: {enabled: false}
                """.formatted(policy) + extra);
        return config;
    }

    @Test
    void runsThePipelineStepByStep(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = use(new InMemoryObjectStore());
        Fixtures.put(store, "in/a.txt", "Mail ann@example.com today.\n");
        Fixtures.put(store, "in/b.log", "SSN 123-45-6789\n");
        final String c = config(dir, "").toString();

        assertEquals(ExitCodes.ERROR, PhilterBatch.execute("work", "-c", c), "no inventory yet");
        assertEquals(ExitCodes.OK, PhilterBatch.execute("inventory", "-c", c));
        assertEquals(ExitCodes.INCOMPLETE, PhilterBatch.execute("finalize", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("work", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("finalize", "-c", c));

        final RunLayout layout = new RunLayout("output-bucket", "out/", "cli-run");
        final StringBuilder out = new StringBuilder();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            out.append(new String(store.get(layout.bucket(), part.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        }
        assertTrue(out.toString().contains("Mail {{{REDACTED-email-address}}} today."));
        assertTrue(out.toString().contains("SSN {{{REDACTED-ssn}}}"));
        assertFalse(out.toString().contains("ann@example.com"));
        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(2, success.counts().records());
    }

    @Test
    void runsThePipelineInOneProcess(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = use(new InMemoryObjectStore());
        Fixtures.put(store, "in/a.txt", "Mail ann@example.com today.\n");
        final String c = config(dir, "output: {bucket: output-bucket, prefix: out/, mode: both}\n").toString();
        assertEquals(ExitCodes.OK, PhilterBatch.execute("run", "-c", c));
        final RunLayout layout = new RunLayout("output-bucket", "out/", "cli-run");
        final ObjectInfo doc = store.list(layout.bucket(), layout.documentsPrefix()).getFirst();
        assertEquals("Mail {{{REDACTED-email-address}}} today.\n",
                new String(store.get(layout.bucket(), doc.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        // Running again resumes, finds everything finished, and does nothing.
        assertEquals(ExitCodes.OK, PhilterBatch.execute("run", "-c", c));
    }

    @Test
    void reportsErrorsAndUsage(@TempDir final Path dir) throws Exception {
        use(new InMemoryObjectStore());
        assertEquals(ExitCodes.USAGE, PhilterBatch.execute("inventory"));
        assertEquals(ExitCodes.USAGE, PhilterBatch.execute("nonsense"));
        assertEquals(ExitCodes.ERROR, PhilterBatch.execute("inventory", "-c", dir.resolve("missing.yaml").toString()));
        final Path noPolicy = dir.resolve("np.yaml");
        Files.writeString(noPolicy, "run_id: x\ninput: {bucket: a}\noutput: {bucket: b}\n");
        assertEquals(ExitCodes.ERROR, PhilterBatch.execute("inventory", "-c", noPolicy.toString()));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("--version"));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("--help"));
    }

    @Test
    void checkStorePassesOnAConformingStore(@TempDir final Path dir) throws Exception {
        final InMemoryObjectStore store = use(new InMemoryObjectStore());
        assertEquals(ExitCodes.OK, PhilterBatch.execute("check-store", "-c", config(dir, "").toString()));
        assertTrue(store.list("output-bucket", "").isEmpty(), "the check cleans up");
    }

    @Test
    void checkStoreFailsOnAStoreThatIgnoresConditions(@TempDir final Path dir) throws Exception {
        // A store that accepts every write, as a store without conditional-write support would.
        final InMemoryObjectStore inner = new InMemoryObjectStore();
        final ObjectStore lax = new ForwardingStore(inner) {
            @Override
            public PutResult putIfAbsent(final String bucket, final String key, final byte[] data, final String type) {
                return inner.put(bucket, key, data, type);
            }

            @Override
            public PutResult putIfMatch(final String bucket, final String key, final byte[] data, final String type,
                                        final String etag) {
                return inner.put(bucket, key, data, type);
            }
        };
        final RunConfig config = ai.philterd.batch.config.ConfigLoader.load(config(dir, ""));
        assertEquals(ExitCodes.ERROR, CheckStoreCommand.check(lax, config, Clock.systemUTC()));
        assertEquals(ExitCodes.ERROR, CheckStoreCommand.check(inner, config,
                Clock.offset(Clock.systemUTC(), java.time.Duration.ofHours(1))));
    }

    /** Delegates every call, for tests that override one. */
    static class ForwardingStore implements ObjectStore {
        private final ObjectStore d;

        ForwardingStore(final ObjectStore d) {
            this.d = d;
        }

        public java.util.List<ObjectInfo> list(final String b, final String p) {
            return d.list(b, p);
        }

        public java.util.Optional<ai.philterd.batch.store.StoredObject> get(final String b, final String k) {
            return d.get(b, k);
        }

        public ai.philterd.batch.store.StoredObject getIfMatch(final String b, final String k, final String e) {
            return d.getIfMatch(b, k, e);
        }

        public java.io.InputStream openRange(final String b, final String k, final String e, final long s, final long x) {
            return d.openRange(b, k, e, s, x);
        }

        public PutResult put(final String b, final String k, final byte[] data, final String t) {
            return d.put(b, k, data, t);
        }

        public PutResult putIfAbsent(final String b, final String k, final byte[] data, final String t) {
            return d.putIfAbsent(b, k, data, t);
        }

        public PutResult putIfMatch(final String b, final String k, final byte[] data, final String t, final String e) {
            return d.putIfMatch(b, k, data, t, e);
        }

        public void delete(final String b, final String k) {
            d.delete(b, k);
        }

        public void compose(final String b, final String t, final java.util.List<String> s, final String c) {
            d.compose(b, t, s, c);
        }

        public java.time.Instant serverTime(final String b) {
            return d.serverTime(b);
        }
    }

}
