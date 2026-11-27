/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.finalize.Finalizer;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Decision;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.worker.StopSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "run", mixinStandardHelpOptions = true,
        description = {"Run the whole pipeline in this process, without Argo.",
                "Inventory, the sample, the sample gate, workers, and finalize. Waits at the sample gate for approve",
                "or reject from another shell. Does not wait at the release gate. Uses the same coordination in",
                "object storage, so it can be stopped and run again to resume.",
                "Exit codes: 0 finalized or rejected, 1 error, 3 stopped by a signal, 6 finalized but the",
                "quarantined entries exceed quarantine.max_percent (run has no release gate to hold it)."})
public class RunCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(RunCommand.class);

    @Mixin
    Common common;

    @Mixin
    WorkerOptions workerOptions;

    @Option(names = "--poll-seconds", defaultValue = "10",
            description = "How often to check the sample gate (default: ${DEFAULT-VALUE}).")
    int pollSeconds;

    @Override
    public Integer call() throws Exception {
        try (Common.Session session = common.open(workerOptions)) {
            final StopSignal stop = new StopSignal();
            stop.installHandlers();
            WorkCommand.exitAfterGrace(stop, session.config());
            return run(session.store(), session.config(), session.policy(), stop, Duration.ofSeconds(pollSeconds));
        }
    }

    static int run(final ObjectStore store, final RunConfig config, final byte[] policy, final StopSignal stop,
                   final Duration poll) throws Exception {
        InventoryCommand.inventory(store, config, policy);
        final int sample = SampleCommand.sample(store, config, stop, 1);
        if (sample != ExitCodes.OK) {
            return sample;
        }
        final Gates gates = new Gates(store, config);
        final Optional<Decision> decision = gates.await(Gate.SAMPLE, poll, stop);
        if (decision.isEmpty()) {
            return ExitCodes.STOPPED;
        }
        if (decision.get() == Decision.REJECTED) {
            LOGGER.info("The sample of run {} was rejected; the run ends.", config.runId());
            return ExitCodes.OK;
        }
        final int work = WorkerFactory.create(store, config, stop).run(true);
        if (work != ExitCodes.OK) {
            return work;
        }
        final int finalized = new Finalizer(store, config, Clock.systemUTC()).run();
        return finalized == ExitCodes.OK ? FinalizeCommand.quarantineCheck(store, config, false) : finalized;
    }

}
