/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "sample-report", mixinStandardHelpOptions = true,
        description = {"Write a run's sample report to standard output.",
                "The HTML report, or with --json the JSON.",
                "Example: kubectl exec <console> -- philter-batch sample-report <run_id> > sample-report.html"})
public class SampleReportCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(index = "0", paramLabel = "RUN_ID", description = "The run.")
    String runId;

    @Option(names = "--json", description = "The JSON report instead of the HTML.")
    boolean json;

    @Override
    public Integer call() {
        if (!ConfigLoader.RUN_ID.matcher(runId).matches()) {
            throw new ConfigException("A run ID is letters, digits, '.', '_', and '-', up to 64 characters.");
        }
        final Deployment d = console.deployment();
        try (ObjectStore store = console.store(d)) {
            final RunLayout layout = new RunLayout(d.output().bucket(), d.output().prefix(), runId);
            final byte[] report = store.get(layout.bucket(), json ? layout.sampleReport() : layout.sampleReportHtml())
                    .orElseThrow(() -> new ConfigException("Run " + runId + " has no sample report yet.")).data();
            Console.out.write(report, 0, report.length);
            Console.out.flush();
        }
        return ExitCodes.OK;
    }

}
