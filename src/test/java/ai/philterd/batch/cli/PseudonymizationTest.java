/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.keys.FakeSecrets;
import ai.philterd.batch.keys.MasterSecret;
import ai.philterd.batch.keys.RunKeys;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Formats.SampleReport;
import ai.philterd.batch.run.Formats.TextRecord;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs with keyed strategies, through the CLI as the workflow's pods run it, with Secrets Manager replaced by
 * {@link FakeSecrets}. Each command stands for a process: the process's reads of the secret are forgotten before it.
 * Every SSN, address, and secret value is invented.
 */
class PseudonymizationTest {

    /** SSNs by FPE, email addresses by AES-GCM, and the strategies the reviewer is warned about. */
    static final String KEYED_POLICY = """
            {"name":"keyed","identifiers":{
              "ssn":{"ssnFilterStrategies":[{"strategy":"FPE_ENCRYPT_REPLACE"}]},
              "emailAddress":{"emailAddressFilterStrategies":[{"strategy":"CRYPTO_REPLACE"}]},
              "creditCard":{"creditCardFilterStrategies":[{"strategy":"LAST_4"}]},
              "phoneNumber":{"phoneNumberFilterStrategies":[{"strategy":"HASH_SHA256_REPLACE"}]}}}
            """;

    private static final String V1 = "an invented pseudonymization secret, version one, over 32 bytes";
    private static final String V2 = "an invented pseudonymization secret, version two, over 32 bytes";
    private static final Pattern LINE = Pattern.compile("Record (\\d+): SSN (\\S+) for (\\S+) end");

    private final Function<RunConfig.Store, ObjectStore> originalStore = Common.storeFactory;
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private FakeSecrets secrets;
    private final StringWriter logs = new StringWriter();
    private WriterAppender appender;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        Common.storeFactory = s -> store;
        secrets = new FakeSecrets().put("v1", V1).install();
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        appender = WriterAppender.newBuilder().setName("capture").setTarget(logs)
                .setLayout(PatternLayout.newBuilder().withPattern("%m%n").build()).build();
        appender.start();
        context.getConfiguration().addAppender(appender);
        context.getConfiguration().getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();
    }

    @AfterEach
    void restore() {
        Common.storeFactory = originalStore;
        FakeSecrets.uninstall();
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().getRootLogger().removeAppender("capture");
        context.updateLoggers();
        appender.stop();
    }

    /** {@code files} invented records, each naming one of 7 SSNs and one of 5 addresses. */
    private void input(final int files) {
        for (int i = 0; i < files; i++) {
            Fixtures.put(store, "in/record-" + i + ".txt", "Record " + i + ": SSN " + ssn(i % 7) + " for "
                    + email(i % 5) + " end\n");
        }
    }

    private static String ssn(final int n) {
        return "123-45-67" + String.format(Locale.ROOT, "%02d", n + 10);
    }

    private static String email(final int n) {
        return "person" + n + "@example.com";
    }

    private Path config(final String runId, final String extra) throws Exception {
        final Path policy = dir.resolve(runId + "-policy.json");
        Files.writeString(policy, KEYED_POLICY);
        final Path config = dir.resolve(runId + ".yaml");
        Files.writeString(config, """
                run_id: %s
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: out/}
                policy: %s
                lease: {initial_seconds: 60, renew_seconds: 10}
                worker: {threads: 2, onnx_threads: 1}
                inventory: {shard_target_bytes: 200}
                sample: {enabled: false}
                pseudonymization: {secret_arn: %s}
                """.formatted(runId, policy, FakeSecrets.ARN) + extra);
        return config;
    }

    /** Runs a command as its own process would. */
    private static int process(final String... args) {
        ai.philterd.batch.keys.Secrets.forget();
        return PhilterBatch.execute(args);
    }

    /** The run's records: record number to its redacted line's SSN and address replacements. */
    private Map<Integer, String[]> records(final String runId) {
        final RunLayout layout = new RunLayout("output-bucket", "out/", runId);
        final Map<Integer, String[]> out = new HashMap<>();
        for (final ObjectInfo part : store.list(layout.bucket(), layout.partsPrefix())) {
            for (final String line : new String(store.get(layout.bucket(), part.key()).orElseThrow().data(),
                    StandardCharsets.UTF_8).split("\n")) {
                final TextRecord record = Json.read(line.getBytes(StandardCharsets.UTF_8), TextRecord.class);
                final Matcher m = LINE.matcher(record.text());
                assertTrue(m.find(), record.text());
                out.put(Integer.parseInt(m.group(1)), new String[]{m.group(2), m.group(3)});
            }
        }
        return out;
    }

    @Test
    void identifiersGetOneFpeReplacementAcrossShardsWorkersAndARotation() throws Exception {
        input(28);
        final String c = config("keyed-run", "").toString();
        assertEquals(ExitCodes.OK, process("inventory", "-c", c));

        // The run pins version v1. The secret then rotates; the run's workers keep v1.
        final RunLayout layout = new RunLayout("output-bucket", "out/", "keyed-run");
        final RunSettings settings = Json.read(store.get(layout.bucket(), layout.settings()).orElseThrow().data(),
                RunSettings.class);
        assertEquals("v1", settings.key().versionId());
        assertEquals(FakeSecrets.ARN, settings.key().secretArn());
        secrets.put("v2", V2);

        // Three workers at once, sharing the shards, then a resumed run that finds them done.
        final List<Thread> workers = new ArrayList<>();
        final List<Integer> exits = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 3; i++) {
            workers.add(Thread.ofPlatform().start(() -> exits.add(PhilterBatch.execute("work", "-c", c,
                    "--threads", "1", "--wait"))));
        }
        for (final Thread t : workers) {
            t.join();
        }
        assertEquals(List.of(0, 0, 0), exits);
        final Set<String> holders = new HashSet<>();
        for (final ObjectInfo done : store.list(layout.bucket(), layout.donePrefix())) {
            holders.add(Json.read(store.get(layout.bucket(), done.key()).orElseThrow().data(),
                    ai.philterd.batch.run.Formats.DoneMarker.class).holder());
        }
        assertTrue(holders.size() > 1, "the shards were shared: " + holders);
        assertEquals(ExitCodes.OK, process("run", "-c", c));

        final Map<Integer, String[]> records = records("keyed-run");
        assertEquals(28, records.size());
        final Map<String, Set<String>> ssnReplacements = new HashMap<>();
        final Map<String, Set<String>> emailReplacements = new HashMap<>();
        for (final Map.Entry<Integer, String[]> e : records.entrySet()) {
            final String[] r = e.getValue();
            assertNotEquals(ssn(e.getKey() % 7), r[0]);
            assertTrue(r[0].matches("\\d{3}-\\d{2}-\\d{4}"), "FPE keeps the format: " + r[0]);
            ssnReplacements.computeIfAbsent(ssn(e.getKey() % 7), k -> new HashSet<>()).add(r[0]);
            emailReplacements.computeIfAbsent(email(e.getKey() % 5), k -> new HashSet<>()).add(r[1]);
        }
        // One FPE replacement per SSN everywhere, and no two SSNs share one.
        final Set<String> all = new HashSet<>();
        for (final Set<String> replacements : ssnReplacements.values()) {
            assertEquals(1, replacements.size(), ssnReplacements.toString());
            all.addAll(replacements);
        }
        assertEquals(7, all.size());
        // AES-GCM has a random nonce: the same address gets different replacements.
        assertTrue(emailReplacements.values().stream().anyMatch(s -> s.size() > 1), emailReplacements.toString());

        // A second run under the same version gives the same replacements; one under v2 gives others.
        final String same = config("keyed-run-again", "").toString();
        secrets.versions.remove("v2");
        secrets.put("v1", V1);
        assertEquals(ExitCodes.OK, process("run", "-c", same));
        final Map<Integer, String[]> again = records("keyed-run-again");
        for (final Map.Entry<Integer, String[]> e : records.entrySet()) {
            assertEquals(e.getValue()[0], again.get(e.getKey())[0]);
        }
        secrets.put("v2", V2);
        assertEquals(ExitCodes.OK, process("run", "-c", config("keyed-run-v2", "").toString()));
        final Map<Integer, String[]> rotated = records("keyed-run-v2");
        assertNotEquals(records.get(0)[0], rotated.get(0)[0]);

        assertNoKeyMaterial();
    }

    @Test
    void readsTheSecretOncePerProcessWhateverTheNumberOfEntries() throws Exception {
        input(4);
        assertEquals(ExitCodes.OK, process("run", "-c", config("small", "").toString()));
        final int small = secrets.reads.getAndSet(0);
        for (int i = 0; i < 60; i++) {
            Fixtures.put(store, "in/large/record-" + i + ".txt", "Record " + i + ": SSN " + ssn(i % 7) + " for "
                    + email(i % 5) + " end\n");
        }
        assertEquals(ExitCodes.OK, process("run", "-c", config("large", "").toString()));
        assertEquals(small, secrets.reads.get());
        // One read for the process: inventory reads the current version, and the workers reuse it as the pin.
        assertEquals(1, small);

        // Step by step, one read per process that needs the key, and none for finalize.
        secrets.reads.set(0);
        final String c = config("steps", "").toString();
        assertEquals(ExitCodes.OK, process("inventory", "-c", c));
        assertEquals(ExitCodes.OK, process("work", "-c", c));
        assertEquals(ExitCodes.OK, process("work", "-c", c));
        assertEquals(ExitCodes.OK, process("finalize", "-c", c));
        assertEquals(3, secrets.reads.get());
    }

    @Test
    void aWorkerGivenAnotherKeyStopsBeforeWritingOutput() throws Exception {
        input(6);
        final String c = config("mismatch", "").toString();
        assertEquals(ExitCodes.OK, process("inventory", "-c", c));
        final List<String> before = keys();

        // The pinned version now holds another value (a secret recreated under the same name, in effect).
        secrets.versions.put("v1", V2.getBytes(StandardCharsets.UTF_8));
        assertEquals(ExitCodes.ERROR, process("work", "-c", c));
        assertTrue(logs.toString().contains("its fingerprint differs"), logs.toString());
        // A configuration naming another secret.
        secrets.versions.put("v1", V1.getBytes(StandardCharsets.UTF_8));
        final Path other = dir.resolve("other.yaml");
        Files.writeString(other, Files.readString(Path.of(c)).replace(FakeSecrets.ARN,
                "arn:aws:secretsmanager:us-east-1:111122223333:secret:other-ZyXwVu"));
        assertEquals(ExitCodes.ERROR, process("work", "-c", other.toString()));
        assertEquals(before, keys(), "nothing was written");

        // With the right key, the run goes on.
        assertEquals(ExitCodes.OK, process("work", "-c", c));
    }

    @Test
    void aKeyedPolicyNeedsASecretAndMayNotHoldAKey() throws Exception {
        input(2);
        final String none = config("no-secret", "").toString();
        Files.writeString(Path.of(none), Files.readString(Path.of(none)).replaceAll("pseudonymization: .*\n", ""));
        assertEquals(ExitCodes.ERROR, process("inventory", "-c", none));
        assertTrue(logs.toString().contains("need a key, and the deployment has no pseudonymization secret"),
                logs.toString());
        assertTrue(store.list("output-bucket", "out/").isEmpty());

        for (final String block : List.of("\"fpe\":{\"key\":\"2DE79D232DF5585D68CE47882AE256D6\",\"tweak\":\"CBD09280979564\"}",
                "\"fpe\":{\"key\":\"env:HOME\"}", "\"crypto\":{\"key\":\"${HOME}\"}", "\"crypto\":{\"iv\":\"00\"}")) {
            final Path policy = dir.resolve("with-key.json");
            Files.writeString(policy, KEYED_POLICY.replace("{\"name\"", "{" + block + ",\"name\""));
            final Path c = dir.resolve("with-key.yaml");
            Files.writeString(c, Files.readString(config("with-key", "")).replaceAll("policy: .*\n",
                    Matcher.quoteReplacement("policy: " + policy + "\n")));
            assertEquals(ExitCodes.ERROR, process("inventory", "-c", c.toString()), block);
        }
        assertTrue(logs.toString().contains("Philter Batch does not take keys in a policy"), logs.toString());
        assertFalse(logs.toString().contains("2DE79D232DF5585D68CE47882AE256D6"), "the refused key is not echoed");
        assertTrue(store.list("output-bucket", "out/").isEmpty());
    }

    @Test
    void theSampleReportWarnsAboutRiskyStrategies() throws Exception {
        input(6);
        final String c = config("warned", "sample: {enabled: true, size: 3}\n").toString();
        assertEquals(ExitCodes.OK, process("inventory", "-c", c));
        assertTrue(logs.toString().contains("uses LAST_4"), logs.toString());
        assertEquals(ExitCodes.OK, process("sample", "-c", c));
        final RunLayout layout = new RunLayout("output-bucket", "out/", "warned");
        final SampleReport report = Json.read(store.get(layout.bucket(), layout.sampleReport()).orElseThrow().data(),
                SampleReport.class);
        final List<String> warned = new ArrayList<>();
        report.policyWarnings().forEach(w -> warned.add(w.strategy() + " " + w.where()));
        assertEquals(List.of("CRYPTO_REPLACE identifiers.emailAddress.emailAddressFilterStrategies[0]",
                "LAST_4 identifiers.creditCard.creditCardFilterStrategies[0]",
                "HASH_SHA256_REPLACE identifiers.phoneNumber.phoneNumberFilterStrategies[0]"), warned);
        final String html = new String(store.get(layout.bucket(), layout.sampleReportHtml()).orElseThrow().data(),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("<h2>Policy warnings</h2>"), html);
        assertTrue(html.contains("<b>LAST_4</b>"), html);
        assertNoKeyMaterial();
    }

    @Test
    void fpeKeyPrintsTheRunsKeyAndNothingElseWritesIt() throws Exception {
        input(3);
        final String c = config("printed", "").toString();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream original = Console.out;
        Console.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        try {
            assertEquals(ExitCodes.OK, process("inventory", "-c", c));
            secrets.put("v2", V2);
            assertEquals(ExitCodes.OK, process("fpe-key", "-c", c));
        } finally {
            Console.out = original;
        }
        final RunKeys v1 = RunKeys.derive(FakeSecrets.ARN, new MasterSecret(V1.getBytes(StandardCharsets.UTF_8), "v1"));
        assertEquals("{\"fpe\":{\"key\":\"" + v1.fpe().getKey() + "\",\"tweak\":\"" + v1.fpe().getTweak() + "\"}}",
                out.toString(StandardCharsets.UTF_8).strip(), "the pinned version's, not the current one's");
        assertNoKeyMaterial();
    }

    private List<String> keys() {
        return store.list("output-bucket", "out/").stream().map(ObjectInfo::key).sorted().toList();
    }

    /** No secret value or derived key, as text or as raw bytes, in any object or log line. */
    private void assertNoKeyMaterial() {
        final List<String> forbidden = new ArrayList<>();
        for (final String value : List.of(V1, V2)) {
            forbidden.add(value);
            final RunKeys keys = RunKeys.derive(FakeSecrets.ARN, new MasterSecret(value.getBytes(StandardCharsets.UTF_8), "v"));
            for (final String hex : List.of(keys.fpe().getKey(), keys.fpe().getTweak(), keys.crypto().getKey())) {
                forbidden.add(hex);
                forbidden.add(hex.toUpperCase(Locale.ROOT));
                forbidden.add(new String(java.util.HexFormat.of().parseHex(hex), StandardCharsets.ISO_8859_1));
            }
        }
        for (final String bucket : List.of("output-bucket", "input-bucket")) {
            for (final ObjectInfo o : store.list(bucket, "")) {
                final String data = new String(store.get(bucket, o.key()).orElseThrow().data(), StandardCharsets.ISO_8859_1);
                for (final String f : forbidden) {
                    assertFalse(data.contains(f), "key material in " + o.key());
                }
            }
        }
        assertNotNull(logs.toString());
        for (final String f : forbidden) {
            assertFalse(logs.toString().contains(f), "key material in the logs");
        }
    }

}
