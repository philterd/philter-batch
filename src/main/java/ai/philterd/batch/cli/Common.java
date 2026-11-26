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
import ai.philterd.batch.store.ObjectStores;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * How a command names its run: a local configuration file ({@code -c}), or a run ID in a deployment
 * ({@code --run-id}), whose configuration and policy were stored in the bucket by {@code configure} and
 * {@code set-policy}.
 */
public class Common {

    @ArgGroup(exclusive = true, multiplicity = "1")
    Source source;

    static class Source {
        @Option(names = {"-c", "--config"}, required = true, description = "The run's YAML configuration file.")
        Path config;

        @Option(names = "--run-id", required = true, description = {"A run stored in the deployment's bucket by "
                + "configure.", "The deployment file is --deployment or $" + Deployment.ENVIRONMENT + "."})
        String runId;
    }

    @Option(names = "--deployment", description = "The deployment file (default: $" + Deployment.ENVIRONMENT + ").")
    Path deployment;

    /** Builds the store; tests replace it with an in-memory store. */
    static Function<RunConfig.Store, ObjectStore> storeFactory = s -> ObjectStores.create(s,
            Math.max(64, Runtime.getRuntime().availableProcessors() * 8));

    /** The environment; tests replace it. */
    static Function<String, String> environment = System::getenv;

    /** A run's configuration and an open store. */
    public record Session(RunConfig config, ObjectStore store, boolean stored) implements AutoCloseable {

        /** The policy's bytes: stored with the run by set-policy, or the configuration's policy file. */
        public byte[] policy() {
            if (stored) {
                final RunLayout layout = new RunLayout(config);
                return store.get(layout.bucket(), layout.storedPolicy()).orElseThrow(() -> new ConfigException(
                        "Run " + config.runId() + " has no policy: store one with set-policy.")).data();
            }
            if (config.policy() == null) {
                throw new ConfigException("policy (the Phileas policy file) is required.");
            }
            try {
                return Files.readAllBytes(Path.of(config.policy()));
            } catch (final IOException e) {
                throw new ConfigException("Could not read the policy file: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            store.close();
        }
    }

    /** Opens the run, applying the pod's worker settings when given. */
    Session open(final WorkerOptions overrides) {
        if (source.config != null) {
            final RunConfig config = overrides == null ? ConfigLoader.load(source.config)
                    : overrides.apply(ConfigLoader.load(source.config));
            return new Session(config, store(config.store()), false);
        }
        final Deployment d = deployment(deployment);
        final ObjectStore store = store(d.store());
        try {
            final RunConfig config = stored(store, d, source.runId);
            return new Session(overrides == null ? config : overrides.apply(config), store, true);
        } catch (final RuntimeException e) {
            store.close();
            throw e;
        }
    }

    Session open() {
        return open(null);
    }

    /** A run's stored configuration. */
    static RunConfig stored(final ObjectStore store, final Deployment d, final String runId) {
        if (runId == null || !ConfigLoader.RUN_ID.matcher(runId).matches()) {
            throw new ConfigException("A run ID is letters, digits, '.', '_', and '-', up to 64 characters.");
        }
        final RunLayout layout = new RunLayout(d.output().bucket(), d.output().prefix(), runId);
        final byte[] yaml = store.get(layout.bucket(), layout.storedConfig()).orElseThrow(() -> new ConfigException(
                "Run " + runId + " is not configured: store its configuration with configure.")).data();
        final RunConfig config = ConfigLoader.parseStored(new String(yaml, StandardCharsets.UTF_8), d);
        if (!config.runId().equals(runId)) {
            throw new ConfigException("The configuration stored for run " + runId + " names run " + config.runId() + ".");
        }
        return config;
    }

    /** The deployment file: the given path, or the environment's. */
    static Deployment deployment(final Path given) {
        Path path = given;
        if (path == null) {
            final String env = environment.apply(Deployment.ENVIRONMENT);
            if (env == null || env.isBlank()) {
                throw new ConfigException("No deployment: pass --deployment or set " + Deployment.ENVIRONMENT + ".");
            }
            path = Path.of(env);
        }
        return ConfigLoader.loadDeployment(path);
    }

    static ObjectStore store(final RunConfig.Store store) {
        return storeFactory.apply(store);
    }

    static int error() {
        return ExitCodes.ERROR;
    }

}
