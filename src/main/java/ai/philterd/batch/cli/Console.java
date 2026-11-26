/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.console.KubernetesWorkflows;
import ai.philterd.batch.console.Workflows;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Supplier;

/**
 * What the console commands share: the deployment, the store, the cluster's workflows, and standard input and output.
 * Command output goes to standard output; logs go to standard error, so output can be redirected to a file.
 */
public class Console {

    /** Replaced by tests. */
    static PrintStream out = System.out;
    static InputStream in = System.in;
    static Supplier<Workflows> workflows = () -> KubernetesWorkflows.inCluster(Common.environment);
    static Clock clock = Clock.systemUTC();

    @Option(names = "--deployment", description = "The deployment file (default: $" + Deployment.ENVIRONMENT + ").")
    Path deployment;

    Deployment deployment() {
        return Common.deployment(deployment);
    }

    ObjectStore store(final Deployment d) {
        return Common.store(d.store());
    }

    /** Reads standard input or a file: exactly one must be given. */
    static byte[] read(final boolean stdin, final Path file, final String what) {
        if (stdin == (file != null)) {
            throw new ConfigException("Give the " + what + " with --stdin or as a file, not both or neither.");
        }
        try {
            return stdin ? in.readAllBytes() : Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new ConfigException("Could not read the " + what + ": " + e.getMessage(), e);
        }
    }

    /** The bucket URI of a key, for messages. */
    static String uri(final Deployment d, final String key) {
        return "s3://" + d.output().bucket() + "/" + key;
    }

}
