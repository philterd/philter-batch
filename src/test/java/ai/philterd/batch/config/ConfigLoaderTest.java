/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.config;

import ai.philterd.batch.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String MINIMAL = """
            run_id: pilot-1
            input: {bucket: in-bucket, prefix: docs/}
            output: {bucket: out-bucket}
            policy: /etc/philter-batch/policy.json
            """;

    @Test
    void appliesDefaults() {
        final RunConfig c = ConfigLoader.parse(MINIMAL);
        assertEquals("pilot-1", c.runId());
        assertEquals("s3", c.store().type());
        assertFalse(c.store().pathStyle());
        assertEquals(RunConfig.OutputMode.TEXT, c.output().mode());
        assertEquals("", c.output().prefix());
        assertEquals(List.of("txt", "md", "log"), c.plainText().extensions());
        assertEquals(StandardCharsets.UTF_8, c.plainText().charsetValue());
        assertEquals(128L * 1024 * 1024, c.inventory().shardTargetBytes());
        assertEquals(600, c.lease().initialSeconds());
        assertTrue(c.worker().threads() >= 1);
        assertEquals("/opt/philter-batch/models", c.modelsDirectory());
    }

    @Test
    void readsEverySetting(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("run.yaml");
        Files.writeString(file, """
                run_id: pilot-2
                store: {type: s3, region: us-east-2, endpoint: "http://localhost:9000", path_style: true}
                input: {bucket: in-bucket, prefix: docs/}
                output: {bucket: out-bucket, prefix: redacted/, mode: both}
                policy: policy.json
                models_directory: /models
                policy_files_directories: [/policy-files]
                plain_text: {extensions: [TXT, .csv], charset: ISO-8859-1}
                inventory: {shard_target_bytes: 1000, max_entries_per_shard: 5, segment_bytes: 100, max_file_bytes: 10000, max_line_bytes: 50}
                lease: {initial_seconds: 90, max_seconds: 900, renew_seconds: 30, expiry_grace_seconds: 2, max_clock_skew_seconds: 10, poison_attempts: 2}
                worker: {threads: 3, onnx_threads: 2, flush_bytes: 10, flush_seconds: 5, file_timeout_seconds: 7, shutdown_grace_seconds: 8}
                """);
        final RunConfig c = ConfigLoader.load(file);
        assertEquals(RunConfig.OutputMode.BOTH, c.output().mode());
        assertTrue(c.output().mode().text() && c.output().mode().document());
        assertEquals(List.of("txt", "csv"), c.plainText().extensions());
        assertEquals("ISO-8859-1", c.plainText().charsetValue().name());
        assertTrue(c.store().pathStyle());
        assertEquals(2, c.lease().poisonAttempts());
        assertEquals(3, c.worker().threads());
        assertEquals(List.of("/policy-files"), c.policyFilesDirectories());
    }

    @Test
    void rejectsInvalidConfigurations() {
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(""));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL.replace("pilot-1", "bad/id")));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "unknown_setting: 1\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL.replace("output: {bucket: out-bucket}",
                "output: {bucket: in-bucket, prefix: docs/redacted/}")));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "plain_text: {charset: NOPE-42}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "plain_text: {extensions: []}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "plain_text: {extensions: [\"t x\"]}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "lease: {initial_seconds: 60, renew_seconds: 30}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "lease: {initial_seconds: 600, max_seconds: 60}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "worker: {threads: 0}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "store: {type: gcs}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "output: {bucket: x, mode: pdf}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(Path.of("/nonexistent/run.yaml")));
    }

    @Test
    void theExampleIsValid() throws Exception {
        final RunConfig c = ConfigLoader.load(Path.of("examples/run.yaml"));
        assertEquals("pilot-2026-11", c.runId());
        assertEquals(4, c.worker().threads());
        // The example policy parses; its model is resolved only where the model is installed.
        final byte[] policy = Files.readAllBytes(Path.of("examples/policy.json"));
        assertThrows(ai.philterd.batch.redaction.PolicyException.class,
                () -> ai.philterd.batch.redaction.LoadedPolicy.load(policy, Path.of("/nonexistent")));
    }

    @Test
    void fixtureConfigIsValid() {
        assertEquals("test-run", Fixtures.config("").runId());
    }

    @Test
    void quarantineLimitDefaultsAndChecks() {
        assertEquals(5.0, ConfigLoader.parse(MINIMAL).quarantine().maxPercent());
        assertEquals(2.5, ConfigLoader.parse(MINIMAL + "quarantine: {max_percent: 2.5}\n").quarantine().maxPercent());
        assertEquals(0.0, ConfigLoader.parse(MINIMAL + "quarantine: {max_percent: 0}\n").quarantine().maxPercent());
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "quarantine: {max_percent: -1}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "quarantine: {max_percent: 100.5}\n"));
        final RunConfig.Quarantine limit = new RunConfig.Quarantine(5.0);
        assertFalse(limit.exceeded(5, 100), "at the limit is not above it");
        assertTrue(limit.exceeded(6, 100));
        assertFalse(limit.exceeded(0, 0), "no entries, nothing exceeded");
        assertFalse(new RunConfig.Quarantine(0.0).exceeded(0, 10));
        assertTrue(new RunConfig.Quarantine(0.0).exceeded(1, 10));
    }

    @Test
    void sampleAndReleaseGateDefaultsAndChecks() {
        final RunConfig c = ConfigLoader.parse(MINIMAL);
        assertTrue(c.sample().enabled());
        assertEquals(50, c.sample().size());
        assertEquals(null, c.sample().seed());
        assertEquals(20_000, c.sample().reportMaxChars());
        assertEquals(null, c.sample().pricePerWorkerHour());
        assertEquals("USD", c.sample().currency());
        assertTrue(c.releaseGate().enabled());
        final RunConfig set = ConfigLoader.parse(MINIMAL + """
                sample: {enabled: false, size: 10, seed: 7, report_max_chars: 500, price_per_worker_hour: 0.42,
                         currency: EUR}
                release_gate: {enabled: false}
                """);
        assertFalse(set.sample().enabled());
        assertEquals(7L, set.sample().seed());
        assertEquals(0.42, set.sample().pricePerWorkerHour());
        assertFalse(set.releaseGate().enabled());
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "sample: {size: 0}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "sample: {report_max_chars: 0}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "sample: {price_per_worker_hour: -1}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "sample: {currency: \"US$\"}\n"));
        assertThrows(ConfigException.class, () -> ConfigLoader.parse(MINIMAL + "release_gate: {on: true}\n"));
    }

    private static final Deployment DEPLOYMENT = new Deployment(new RunConfig.Store("s3", "us-east-1", null, null),
            new RunConfig.Location("out-bucket", "philter-batch/"));

    private static final String STORED = """
            run_id: pilot-1
            input: {bucket: in-bucket, prefix: docs/}
            """;

    @Test
    void aStoredConfigurationTakesTheDeploymentsLocation() {
        final RunConfig c = ConfigLoader.parseStored(STORED, DEPLOYMENT);
        assertEquals(DEPLOYMENT.store(), c.store());
        assertEquals("out-bucket", c.output().bucket());
        assertEquals("philter-batch/", c.output().prefix());
        assertEquals(RunConfig.OutputMode.TEXT, c.output().mode());
        final RunConfig both = ConfigLoader.parseStored(STORED + "output: {mode: both}\n", DEPLOYMENT);
        assertEquals(RunConfig.OutputMode.BOTH, both.output().mode());
        assertEquals("out-bucket", both.output().bucket());
        // The same location given explicitly is accepted; a different one is refused.
        ConfigLoader.parseStored(STORED + "store: {type: s3, region: us-east-1}\n"
                + "output: {bucket: out-bucket, prefix: philter-batch/}\n", DEPLOYMENT);
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored(STORED + "store: {region: eu-west-1}\n",
                DEPLOYMENT));
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored(STORED + "output: {prefix: elsewhere/}\n",
                DEPLOYMENT));
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored(STORED + "output: [1]\n", DEPLOYMENT));
        // Settings a deployment sets per pod are refused, with the reason.
        for (final String extra : List.of("policy: p.json\n", "worker: {threads: 2}\n", "worker: {onnx_threads: 2}\n",
                "worker: {shutdown_grace_seconds: 20}\n")) {
            final ConfigException e = assertThrows(ConfigException.class,
                    () -> ConfigLoader.parseStored(STORED + extra, DEPLOYMENT));
            assertTrue(e.getMessage().contains("set by the deployment"), e.getMessage());
        }
        ConfigLoader.parseStored(STORED + "worker: {flush_seconds: 30}\n", DEPLOYMENT);
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored("", DEPLOYMENT));
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored("- a\n", DEPLOYMENT));
        assertThrows(ConfigException.class, () -> ConfigLoader.parseStored("run_id: [", DEPLOYMENT));
    }

    @Test
    void readsADeploymentFile(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("deployment.yaml");
        Files.writeString(file, "store: {type: s3, endpoint: http://store.example.test:9000, path_style: true}\n"
                + "output: {bucket: out-bucket, prefix: pb/}\n");
        final Deployment d = ConfigLoader.loadDeployment(file);
        assertEquals("http://store.example.test:9000", d.store().endpoint());
        assertTrue(d.store().pathStyle());
        assertEquals("pb/", d.output().prefix());
        Files.writeString(file, "output: {prefix: pb/}\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.loadDeployment(file));
        Files.writeString(file, "output: {bucket: b}\nstore: {type: gcs}\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.loadDeployment(file));
        Files.writeString(file, "output: {bucket: b}\nextra: 1\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.loadDeployment(file));
        assertThrows(ConfigException.class, () -> ConfigLoader.loadDeployment(dir.resolve("missing.yaml")));
    }

}
