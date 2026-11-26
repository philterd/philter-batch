/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.console.Workflows;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console's commands, as an operator runs them through kubectl exec, against an in-memory bucket. */
class ConsoleTest {

    private static final String RUN = "console-run";

    private final Function<RunConfig.Store, ObjectStore> originalStore = Common.storeFactory;
    private final Function<String, String> originalEnvironment = Common.environment;
    private final PrintStream originalOut = Console.out;
    private final InputStream originalIn = Console.in;
    private final Supplier<Workflows> originalWorkflows = Console.workflows;
    private final java.time.Clock originalClock = Console.clock;

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final FakeWorkflows workflows = new FakeWorkflows();
    private ByteArrayOutputStream out;

    @TempDir
    Path dir;

    /** Records submissions; each submitted workflow is Running until the test says otherwise. */
    static final class FakeWorkflows implements Workflows {
        final List<Info> workflows = new ArrayList<>();
        boolean unreachable;

        @Override
        public String submit(final String runId) {
            final String name = runId + "-w" + workflows.size();
            workflows.addFirst(new Info(name, runId, "Running", Instant.parse("2026-11-26T12:00:00Z"), null, null));
            return name;
        }

        @Override
        public List<Info> list() {
            if (unreachable) {
                throw new ConfigException("Not in a Kubernetes pod with a service account.");
            }
            return List.copyOf(workflows);
        }

        void finish(final String phase) {
            final Info w = workflows.removeFirst();
            workflows.addFirst(new Info(w.name(), w.runId(), phase, w.started(), Instant.parse("2026-11-26T13:00:00Z"),
                    "Failed".equals(phase) ? "child failed" : null));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Common.storeFactory = s -> store;
        final Path deployment = dir.resolve("deployment.yaml");
        Files.writeString(deployment, """
                store: {type: s3, region: us-east-1}
                output: {bucket: output-bucket, prefix: out/}
                """);
        Common.environment = name -> name.equals("PHILTER_BATCH_DEPLOYMENT") ? deployment.toString() : null;
        Console.workflows = () -> workflows;
        Console.clock = TestClock.at("2026-11-26T12:30:00Z");
        for (int i = 0; i < 12; i++) {
            Fixtures.put(store, "in/letter-" + i + ".txt", "Letter " + i + ": mail avery" + i + "@example.com or "
                    + "call about SSN 123-45-67" + String.format("%02d", i) + ".\n");
        }
    }

    @AfterEach
    void restore() {
        Common.storeFactory = originalStore;
        Common.environment = originalEnvironment;
        Console.out = originalOut;
        Console.in = originalIn;
        Console.workflows = originalWorkflows;
        Console.clock = originalClock;
    }

    /** Runs a command with the given standard input, and returns its exit code; its output is in {@link #out}. */
    private int execIn(final String stdin, final String... args) {
        out = new ByteArrayOutputStream();
        Console.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        Console.in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        return PhilterBatch.execute(args);
    }

    private int exec(final String... args) {
        return execIn("", args);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String runYaml(final String extra) {
        return """
                run_id: console-run
                input: {bucket: input-bucket, prefix: in/}
                output: {mode: text}
                lease: {initial_seconds: 60, renew_seconds: 10}
                sample: {size: 4, price_per_worker_hour: 1.25}
                """ + extra;
    }

    /** Configures the run and stores its policy, as the operator does first. */
    private void configured() {
        assertEquals(ExitCodes.OK, execIn(runYaml(""), "configure", "--stdin"));
        assertEquals(ExitCodes.OK, execIn(Fixtures.REGEX_POLICY, "set-policy", RUN, "--stdin"));
    }

    /** What the workflow's steps do, as its pods run them. */
    private void workflowUntilTheSampleGate() {
        assertEquals(ExitCodes.OK, exec("check-store", "--run-id", RUN));
        assertEquals(ExitCodes.OK, exec("inventory", "--run-id", RUN));
        assertEquals(ExitCodes.OK, exec("sample", "--run-id", RUN, "--threads", "2", "--worker-pods", "3"));
    }

    @Test
    void operatorRunsAWholeRunFromTheConsole() throws Exception {
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("not configured"), output());

        assertEquals(ExitCodes.OK, execIn(runYaml(""), "configure", "--stdin"));
        assertTrue(output().contains("Configured run console-run: s3://output-bucket/out/runs/console-run/config/run.yaml"),
                output());
        assertTrue(output().contains("set-policy console-run"), output());
        assertEquals(ExitCodes.ERROR, exec("submit", RUN), "no policy yet");
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("configured, without a policy"), output());
        assertTrue(output().contains("Sample gate:  not reached"), output());

        assertEquals(ExitCodes.OK, execIn(Fixtures.REGEX_POLICY, "set-policy", RUN, "--stdin"));
        assertTrue(output().contains("Stored the policy of run console-run (sha256:"), output());
        assertFalse(output().contains("leftover check"), output());

        assertEquals(ExitCodes.OK, exec("submit", RUN));
        assertTrue(output().contains("Submitted run console-run as workflow console-run-w0."), output());
        assertEquals(ExitCodes.ERROR, exec("submit", RUN), "already running");
        assertEquals(1, workflows.workflows.size());

        workflowUntilTheSampleGate();
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("waiting for a decision on the sample (waiting on a person)"), output());
        assertTrue(output().contains("console-run-w0, Running"), output());
        assertTrue(output().contains("12 documents"), output());
        assertTrue(output().contains("4 shards, 4 done; report written"), output());
        assertTrue(output().contains("Sample gate:  waiting"), output());
        assertEquals(ExitCodes.ERROR, exec("work", "--run-id", RUN), "not approved");

        assertEquals(ExitCodes.OK, exec("sample-report", RUN));
        final String html = output();
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("title=\"email-address\">{{{REDACTED-email-address}}}</mark>"), html);
        assertTrue(html.contains("title=\"ssn\">{{{REDACTED-ssn}}}</mark>"), html);
        assertFalse(html.contains("@example.com"), "no original value in the report");
        assertFalse(html.contains("123-45-67"), "no original value in the report");
        assertTrue(html.contains("USD (at 1.2500 USD per worker pod hour)"), html);
        assertEquals(ExitCodes.OK, exec("sample-report", RUN, "--json"));
        assertTrue(output().contains("\"worker_pods\":3"), output());

        final Path decision = dir.resolve("gate/decision");
        assertEquals(ExitCodes.OK, exec("approve", RUN, "sample", "--by", "Test Reviewer", "--note", "spot-checked"));
        assertTrue(output().contains("Recorded: the sample gate of run console-run is approved."), output());
        assertEquals(ExitCodes.OK, exec("approve", RUN, "sample"));
        assertTrue(output().contains("Already recorded"), output());
        assertEquals(ExitCodes.ERROR, exec("reject", RUN, "sample"), "already approved");
        assertEquals(ExitCodes.OK, exec("await-gate", "--run-id", RUN, "--gate", "sample", "--decision-file",
                decision.toString()));
        assertEquals("approved\n", output());
        assertEquals("approved", Files.readString(decision));

        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("Sample gate:  approved 2026-11-26T12:30:00Z by Test Reviewer: spot-checked"),
                output());
        assertEquals(ExitCodes.OK, exec("work", "--run-id", RUN, "--threads", "2"));
        assertEquals(ExitCodes.OK, exec("finalize", "--run-id", RUN));
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("waiting for a decision on the release (waiting on a person)"), output());
        assertEquals(ExitCodes.OK, exec("approve", RUN, "release", "--by", "Test Reviewer"));
        assertEquals(ExitCodes.OK, exec("await-gate", "--run-id", RUN, "--gate", "release"));
        assertEquals("approved\n", output());
        workflows.finish("Succeeded");

        assertEquals(ExitCodes.OK, exec("status", RUN, "--json"));
        assertTrue(output().contains("\"stage\":\"FINISHED\""), output());
        assertEquals(ExitCodes.ERROR, exec("submit", RUN), "finished");
        final RunLayout layout = new RunLayout("output-bucket", "out/", RUN);
        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(12, success.counts().records());
        assertEquals(ExitCodes.ERROR, execIn(runYaml(""), "configure", "--stdin"), "a started run's configuration is fixed");
        assertEquals(ExitCodes.ERROR, execIn(Fixtures.REGEX_POLICY, "set-policy", RUN, "--stdin"));
    }

    @Test
    void aRejectedSampleEndsTheRun() {
        configured();
        assertEquals(ExitCodes.OK, exec("submit", RUN));
        workflowUntilTheSampleGate();
        assertEquals(ExitCodes.OK, exec("reject", RUN, "sample", "--by", "Test Reviewer", "--note", "wrong policy"));
        assertEquals(ExitCodes.OK, exec("await-gate", "--run-id", RUN, "--gate", "sample"));
        assertEquals("rejected\n", output());
        workflows.finish("Succeeded");
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("ended: the sample was rejected"), output());
        assertEquals(ExitCodes.ERROR, exec("submit", RUN), "a rejected run is not resubmitted");
        assertEquals(ExitCodes.ERROR, exec("work", "--run-id", RUN));
        assertEquals(ExitCodes.ERROR, exec("approve", RUN, "sample"));
    }

    @Test
    void statusSaysWhenNoWorkflowIsRunningTheRun() {
        configured();
        assertEquals(ExitCodes.OK, exec("submit", RUN));
        assertEquals(ExitCodes.OK, exec("inventory", "--run-id", RUN));
        workflows.finish("Failed");
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("redacting the sample"), output());
        assertTrue(output().contains("no workflow is running this run: philter-batch submit console-run"), output());
        assertTrue(output().contains("Failed"), output());
        assertEquals(ExitCodes.OK, exec("submit", RUN), "a stopped run is resubmitted to resume it");

        workflows.unreachable = true;
        assertEquals(ExitCodes.OK, exec("status", RUN));
        assertTrue(output().contains("unknown (Not in a Kubernetes pod"), output());
    }

    @Test
    void configureChecksTheConfiguration() {
        assertEquals(ExitCodes.ERROR, execIn(runYaml("policy: /config/policy.json\n"), "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(runYaml("worker: {threads: 4}\n"), "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(runYaml("store: {type: s3, region: eu-west-1}\n"), "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(runYaml("").replace("output: {mode: text}", "output: {bucket: other}"),
                "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(runYaml("nonsense: 1\n"), "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn("", "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(runYaml(""), "configure"), "neither --stdin nor a file");
        // The same location, given explicitly, is accepted.
        assertEquals(ExitCodes.OK, execIn(runYaml("store: {type: s3, region: us-east-1}\n").replace(
                "output: {mode: text}", "output: {bucket: output-bucket, prefix: out/, mode: text}"),
                "configure", "--stdin"));
        // set-policy checks the policy, and needs a configured run.
        assertEquals(ExitCodes.ERROR, execIn("{\"identifiers\":", "set-policy", RUN, "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn("{\"identifiers\":{\"person\":{\"phEyeConfiguration\":{\"modelPath\":"
                + "\"not-in-this-image\"}}}}", "set-policy", RUN, "--stdin"));
        assertEquals(ExitCodes.ERROR, execIn(Fixtures.REGEX_POLICY, "set-policy", "other-run", "--stdin"));
        assertEquals(ExitCodes.ERROR, exec("status", "bad/run"));
        assertEquals(ExitCodes.ERROR, exec("sample-report", RUN), "no report yet");
        assertEquals(ExitCodes.ERROR, exec("approve", RUN, "nonsense"));
        assertEquals(ExitCodes.ERROR, exec("approve", RUN), "the gate is required");
    }

    @Test
    void podCommandsReadTheStoredRunAndRefuseAMissingOne() throws Exception {
        assertEquals(ExitCodes.ERROR, exec("inventory", "--run-id", RUN), "not configured");
        assertEquals(ExitCodes.OK, execIn(runYaml(""), "configure", "--stdin"));
        assertEquals(ExitCodes.ERROR, exec("inventory", "--run-id", RUN), "no policy");
        Common.environment = name -> null;
        assertEquals(ExitCodes.ERROR, exec("inventory", "--run-id", RUN), "no deployment");
        assertEquals(ExitCodes.USAGE, exec("inventory", "--run-id", RUN, "-c", "run.yaml"), "one or the other");
        assertEquals(ExitCodes.ERROR, exec("sample", "--run-id", RUN, "--worker-pods", "0"));
    }

    @Test
    void aLocalRunWaitsAtTheSampleGateUntilApproved() throws Exception {
        final Path policy = dir.resolve("policy.json");
        Files.writeString(policy, Fixtures.REGEX_POLICY);
        final Path config = dir.resolve("run.yaml");
        Files.writeString(config, """
                run_id: local-run
                input: {bucket: input-bucket, prefix: in/}
                output: {bucket: output-bucket, prefix: local/}
                policy: %s
                lease: {initial_seconds: 60, renew_seconds: 10}
                worker: {threads: 2, onnx_threads: 1}
                sample: {size: 3}
                """.formatted(policy));
        final Thread approver = new Thread(() -> {
            final RunLayout layout = new RunLayout("output-bucket", "local/", "local-run");
            while (store.get(layout.bucket(), layout.sampleReport()).isEmpty()) {
                Thread.onSpinWait();
            }
            assertEquals(ExitCodes.OK, PhilterBatch.execute("approve", "-c", config.toString(), "sample"));
        });
        approver.start();
        assertEquals(ExitCodes.OK, exec("run", "-c", config.toString(), "--poll-seconds", "1"));
        approver.join();
        final RunLayout layout = new RunLayout("output-bucket", "local/", "local-run");
        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(12, success.counts().records());
        assertEquals(ExitCodes.ERROR, exec("approve", "-c", config.toString(), "local-run", "sample"),
                "with -c, the gate only");
    }

}
