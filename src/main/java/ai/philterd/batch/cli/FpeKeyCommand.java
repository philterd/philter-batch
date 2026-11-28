/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.keys.RunKeys;
import ai.philterd.batch.run.Formats.KeyPin;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.phileas.policy.FPE;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "fpe-key", mixinStandardHelpOptions = true,
        description = {"Print a run's FPE key and tweak, for reversing its FPE_ENCRYPT_REPLACE values elsewhere.",
                "Prints a Phileas policy's fpe block, derived from the version of the pseudonymization secret the run",
                "was started with (or, before the run starts, the current version), to standard output only.",
                "Anyone holding them can reverse the run's FPE values: keep the output out of files and logs.",
                "Example: kubectl exec <console> -- philter-batch fpe-key <run_id>"})
public class FpeKeyCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(index = "0", arity = "0..1", paramLabel = "RUN_ID", description = "The run.")
    String runId;

    @Option(names = {"-c", "--config"}, description = "A local run configuration instead of a run in the deployment.")
    Path config;

    @Override
    public Integer call() {
        if ((runId == null) == (config == null)) {
            throw new ConfigException("Give the run ID, or -c with a local configuration.");
        }
        final RunConfig run;
        final ObjectStore store;
        if (config != null) {
            run = ConfigLoader.load(config);
            store = Common.store(run.store());
        } else {
            final Deployment d = console.deployment();
            store = console.store(d);
            try {
                run = Common.stored(store, d, runId);
            } catch (final RuntimeException e) {
                store.close();
                throw e;
            }
        }
        try (store) {
            if (!run.pseudonymization().configured()) {
                throw new ConfigException("Run " + run.runId() + " has no pseudonymization secret.");
            }
            final RunLayout layout = new RunLayout(run);
            final Optional<KeyPin> pin = store.get(layout.bucket(), layout.settings())
                    .map(o -> Json.read(o.data(), RunSettings.class).key());
            final RunKeys keys = pin.isPresent() ? RunKeys.forPin(run, pin.get())
                    : RunKeys.derive(run.pseudonymization().secretArn(), ai.philterd.batch.keys.Secrets.current(
                            run.pseudonymization()));
            final FPE fpe = keys.fpe();
            final Map<String, String> block = new LinkedHashMap<>();
            block.put("key", fpe.getKey());
            block.put("tweak", fpe.getTweak());
            System.err.println("Version " + keys.pin().versionId() + " of " + keys.pin().secretArn()
                    + (pin.isPresent() ? ", which run " + run.runId() + " was started with."
                    : ", the current version: run " + run.runId() + " has not started, and pins the version current "
                    + "when it does."));
            Console.out.println(new String(Json.write(Map.of("fpe", block)), java.nio.charset.StandardCharsets.UTF_8));
        }
        return ExitCodes.OK;
    }

}
