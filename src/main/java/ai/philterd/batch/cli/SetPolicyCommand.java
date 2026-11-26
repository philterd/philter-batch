/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "set-policy", mixinStandardHelpOptions = true,
        description = {"Store a configured run's Phileas policy in the deployment's bucket.",
                "Checks first that it loads with the models in this image. It can be replaced until the run starts.",
                "Example: kubectl exec -i <console> -- philter-batch set-policy <run_id> --stdin < policy.json"})
public class SetPolicyCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(index = "0", paramLabel = "RUN_ID", description = "The run.")
    String runId;

    @Option(names = "--stdin", description = "Read the policy from standard input.")
    boolean stdin;

    @Parameters(index = "1", arity = "0..1", paramLabel = "FILE", description = "Read the policy from a file.")
    Path file;

    @Override
    public Integer call() {
        final byte[] json = Console.read(stdin, file, "policy");
        final Deployment d = console.deployment();
        try (ObjectStore store = console.store(d)) {
            final RunConfig config = Common.stored(store, d, runId);
            final LoadedPolicy policy = LoadedPolicy.load(json, Path.of(config.modelsDirectory()));
            final RunLayout layout = new RunLayout(config);
            if (store.get(layout.bucket(), layout.settings()).isPresent()) {
                throw new ConfigException("Run " + runId + " has started, so its policy is fixed. Use a new run_id for "
                        + "a new policy.");
            }
            store.put(layout.bucket(), layout.storedPolicy(), json, "application/json");
            Console.out.println("Stored the policy of run " + runId + " (sha256:" + policy.sha256() + ", "
                    + policy.models().size() + " model(s)): " + Console.uri(d, layout.storedPolicy()));
            final String check = WorkerFactory.leftoverCheck(policy);
            if (!"off".equals(check)) {
                Console.out.println("Note: the policy's leftover check (" + check + ") is ignored: Philter Batch does "
                        + "not run it for now.");
            }
            Console.out.println("Next: philter-batch submit " + runId);
        }
        return ExitCodes.OK;
    }

}
