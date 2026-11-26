/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.console.RunStatus;
import ai.philterd.batch.console.Workflows;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "submit", mixinStandardHelpOptions = true,
        description = {"Start a configured run's Argo workflow.",
                "Checks the run's stored configuration and policy first, then submits a workflow from the",
                "deployment's WorkflowTemplate. Submitting a run that stopped resumes it."})
public class SubmitCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(index = "0", paramLabel = "RUN_ID", description = "The run.")
    String runId;

    @Override
    public Integer call() {
        final Deployment d = console.deployment();
        try (ObjectStore store = console.store(d)) {
            final RunConfig config = Common.stored(store, d, runId);
            final RunLayout layout = new RunLayout(config);
            final byte[] policy = store.get(layout.bucket(), layout.storedPolicy()).orElseThrow(() ->
                    new ConfigException("Run " + runId + " has no policy: philter-batch set-policy " + runId
                            + " --stdin < policy.json")).data();
            LoadedPolicy.load(policy, Path.of(config.modelsDirectory()));
            final Workflows workflows = Console.workflows.get();
            final RunStatus status = RunStatus.read(store, d, runId, workflows);
            if (status.stage().ended()) {
                throw new ConfigException("Run " + runId + " has " + status.stage().text() + ".");
            }
            if (status.workflow() != null && status.workflow().active()) {
                throw new ConfigException("Workflow " + status.workflow().name() + " is already running run " + runId
                        + ".");
            }
            final String name = workflows.submit(runId);
            Console.out.println("Submitted run " + runId + " as workflow " + name + ".");
            Console.out.println("Follow it with: philter-batch status " + runId);
        }
        return ExitCodes.OK;
    }

}
