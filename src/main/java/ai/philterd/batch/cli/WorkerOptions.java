/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import picocli.CommandLine.Option;

/**
 * Worker settings that belong to the pod rather than the run: on Kubernetes the Helm chart passes them, from the
 * workers' CPU and thread values, and they replace the run configuration's.
 */
public class WorkerOptions {

    @Option(names = "--threads", description = "Replaces worker.threads.")
    Integer threads;

    @Option(names = "--onnx-threads", description = "Replaces worker.onnx_threads (default with --threads: "
            + "processors / threads).")
    Integer onnxThreads;

    @Option(names = "--shutdown-grace-seconds", description = "Replaces worker.shutdown_grace_seconds.")
    Integer shutdownGraceSeconds;

    RunConfig apply(final RunConfig config) {
        if (threads == null && onnxThreads == null && shutdownGraceSeconds == null) {
            return config;
        }
        final RunConfig.Worker w = config.worker();
        final Integer onnx = onnxThreads != null ? onnxThreads : threads != null ? null : w.onnxThreads();
        final RunConfig replaced = config.withWorker(new RunConfig.Worker(threads != null ? threads : w.threads(), onnx,
                w.flushBytes(), w.flushSeconds(), w.fileTimeoutSeconds(),
                shutdownGraceSeconds != null ? shutdownGraceSeconds : w.shutdownGraceSeconds()));
        ConfigLoader.validate(replaced);
        return replaced;
    }

}
