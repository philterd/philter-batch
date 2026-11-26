/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "configure", mixinStandardHelpOptions = true,
        description = {"Store a run's configuration in the deployment's bucket.",
                "Checks it first. The deployment sets the store and output location; leave them out, or give the",
                "same. A run's configuration can be replaced until the run starts.",
                "Example: kubectl exec -i <console> -- philter-batch configure --stdin < run.yaml"})
public class ConfigureCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Option(names = "--stdin", description = "Read the configuration from standard input.")
    boolean stdin;

    @Parameters(arity = "0..1", paramLabel = "FILE", description = "Read the configuration from a file.")
    Path file;

    @Override
    public Integer call() {
        final byte[] yaml = Console.read(stdin, file, "configuration");
        final Deployment d = console.deployment();
        final RunConfig config = ConfigLoader.parseStored(new String(yaml, StandardCharsets.UTF_8), d);
        try (ObjectStore store = console.store(d)) {
            final RunLayout layout = new RunLayout(config);
            if (store.get(layout.bucket(), layout.settings()).isPresent()) {
                throw new ConfigException("Run " + config.runId() + " has started, so its configuration is fixed. "
                        + "Use a new run_id for a new configuration.");
            }
            store.put(layout.bucket(), layout.storedConfig(), yaml, "application/yaml");
            Console.out.println("Configured run " + config.runId() + ": " + Console.uri(d, layout.storedConfig()));
            if (store.get(layout.bucket(), layout.storedPolicy()).isEmpty()) {
                Console.out.println("Next: philter-batch set-policy " + config.runId() + " --stdin < policy.json");
            } else {
                Console.out.println("Its policy is stored. Next: philter-batch submit " + config.runId());
            }
        }
        return ExitCodes.OK;
    }

}
