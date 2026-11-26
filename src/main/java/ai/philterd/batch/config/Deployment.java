/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.config;

/**
 * Where a deployment keeps its runs: the store and the output location. On Kubernetes the Helm chart writes it, and
 * every pod (the console's included) reads it, so a run is named by its ID alone and its configuration is read from
 * {@code <output.prefix>runs/<run_id>/config/}. See {@code docs/kubernetes.md}.
 * @param store The object store, as in a run's configuration.
 * @param output The output bucket and prefix. A run's {@code output.mode} stays in the run's configuration.
 */
public record Deployment(RunConfig.Store store, RunConfig.Location output) {

    /** The environment variable naming the deployment file, so console commands need no option for it. */
    public static final String ENVIRONMENT = "PHILTER_BATCH_DEPLOYMENT";

    public Deployment {
        store = store == null ? new RunConfig.Store(null, null, null, null) : store;
    }

    /** The output location as a run's output, with the default mode. */
    public RunConfig.Output runOutput(final RunConfig.OutputMode mode) {
        return new RunConfig.Output(output.bucket(), output.prefix(), mode);
    }

}
