/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.worker.StopSignal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "work", mixinStandardHelpOptions = true,
        description = {"Claim and redact shards until none is left to claim.",
                "Refuses to start until the sample is approved, when the run has a sample.",
                "Exit codes: 0 no work left, 1 error, 3 stopped by a signal, 4 every remaining shard is claimed."})
public class WorkCommand implements Callable<Integer> {

    @Mixin
    Common common;

    @Mixin
    WorkerOptions workerOptions;

    @Option(names = "--wait", description = "Wait for other workers' leases to expire instead of exiting with 4.")
    boolean waitForLeases;

    @Override
    public Integer call() throws Exception {
        try (Common.Session session = common.open(workerOptions)) {
            final RunConfig config = session.config();
            new Gates(session.store(), config).requireSampleApproved();
            final StopSignal stop = new StopSignal();
            stop.installHandlers();
            exitAfterGrace(stop, config);
            return WorkerFactory.create(session.store(), config, stop).run(waitForLeases);
        }
    }

    /** After a stop, exits with {@link ExitCodes#STOPPED} once the grace period ends, even mid-file. */
    static void exitAfterGrace(final StopSignal stop, final RunConfig config) {
        final Thread watchdog = new Thread(() -> {
            try {
                while (!stop.stopped()) {
                    Thread.sleep(200);
                }
                Thread.sleep(config.worker().shutdownGraceSeconds() * 1000L);
                System.exit(ExitCodes.STOPPED);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-grace");
        watchdog.setDaemon(true);
        watchdog.start();
    }

}
