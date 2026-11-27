/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.console.RunStatus;
import ai.philterd.batch.console.Workflows;
import ai.philterd.batch.run.Formats.GateDecision;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = {"Show where a run is and whether it waits on a person.",
                "Reads the run's objects in the bucket and its Argo workflows. Exit code 0 in every stage."})
public class StatusCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(index = "0", paramLabel = "RUN_ID", description = "The run.")
    String runId;

    @Option(names = "--json", description = "Print the status as JSON.")
    boolean json;

    @Override
    public Integer call() {
        final Deployment d = console.deployment();
        Workflows workflows;
        String workflowsError = null;
        try {
            workflows = Console.workflows.get();
            workflows.list();
        } catch (final ConfigException e) {
            workflows = null;
            workflowsError = e.getMessage();
        }
        try (ObjectStore store = console.store(d)) {
            final RunStatus s = RunStatus.read(store, d, runId, workflows);
            if (json) {
                Console.out.println(new String(Json.write(s), StandardCharsets.UTF_8));
                return ExitCodes.OK;
            }
            line("Run", s.runId());
            line("Stage", s.stage().text() + (s.waitingOnPerson() ? " (waiting on a person)" : ""));
            if (s.configError() != null) {
                line("Problem", s.configError());
            }
            if (s.next() != null) {
                line("Next", s.next());
            }
            if (s.workflow() != null) {
                final Workflows.Info w = s.workflow();
                line("Workflow", w.name() + ", " + (w.phase() == null ? "Pending" : w.phase())
                        + (w.started() == null ? "" : ", started " + w.started())
                        + (w.finished() == null ? "" : ", ended " + w.finished())
                        + (w.message() == null || w.message().isEmpty() ? "" : ": " + w.message()));
            } else {
                line("Workflow", workflowsError == null ? "none" : "unknown (" + workflowsError + ")");
            }
            if (s.policySha256() != null) {
                line("Policy", "sha256:" + s.policySha256());
            }
            final InventorySummary i = s.inventory();
            if (i != null) {
                line("Inventory", String.format(Locale.ROOT, "%,d documents, %,d bytes, %,d shards (%d in the sample); "
                        + "%,d objects skipped", i.documents(), i.bytes(), i.shards(), i.sampleShards(), i.skippedFiles()));
                if (i.sampleShards() > 0) {
                    line("Sample", i.sampleShards() + " shards, " + s.sampleShardsDone() + " done; report "
                            + (s.sampleReport() ? "written" : "not yet written"));
                }
                line("Shards done", String.format(Locale.ROOT, "%,d of %,d", s.shardsDone(), s.shards()));
            }
            final RunStatus.Quarantine q = s.quarantine();
            if (q != null && q.entries() > 0) {
                line("Quarantined", String.format(Locale.ROOT, "%,d of %,d entries%s (%.2f%%; limit %s%%)%s",
                        q.quarantined(), q.entries(), q.complete() ? "" : " in completed shards", q.percent(),
                        trim(q.maxPercent()), q.exceeded() ? (q.complete() ? ", ABOVE THE LIMIT" : ", above the limit so far")
                                : ""));
                if (!q.byReason().isEmpty()) {
                    final StringBuilder reasons = new StringBuilder();
                    q.byReason().forEach((reason, n) -> reasons.append(reasons.isEmpty() ? "" : ", ")
                            .append(reason).append(' ').append(String.format(Locale.ROOT, "%,d", n)));
                    line("  by reason", reasons.toString());
                }
            }
            line("Sample gate", gate(s.sampleGate(), s.sampleDecision()));
            line("Finalized", s.finalized() ? "yes" : "no");
            line("Release gate", gate(s.releaseGate(), s.releaseDecision()));
        }
        return ExitCodes.OK;
    }

    private static String gate(final String state, final GateDecision record) {
        if (state == null) {
            return "not reached";
        }
        if (record == null) {
            return state;
        }
        return state + " " + record.decided() + (record.by() == null ? "" : " by " + record.by())
                + (record.note() == null ? "" : ": " + record.note());
    }

    /** 5.0 as 5, 2.5 as 2.5. */
    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static void line(final String name, final String value) {
        Console.out.printf(Locale.ROOT, "%-14s%s%n", name + ":", value);
    }

}
