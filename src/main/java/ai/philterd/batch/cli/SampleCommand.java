/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.sample.SampleReporter;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.worker.StopSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "sample", mixinStandardHelpOptions = true,
        description = {"Redact the run's sample and write its reports.",
                "Writes the sample's output and reports under sample/. Does nothing when the run has no sample or",
                "the sample report exists. Measures the throughput for the estimate, so run it on a pod shaped like",
                "the workers.",
                "Exit codes: 0 done, 1 error, 3 stopped by a signal."})
public class SampleCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(SampleCommand.class);

    @Mixin
    Common common;

    @Mixin
    WorkerOptions workerOptions;

    @Option(names = "--worker-pods", defaultValue = "1",
            description = "The worker pods of the full run, for the estimate (default: ${DEFAULT-VALUE}).")
    int workerPods;

    @Override
    public Integer call() throws Exception {
        if (workerPods < 1) {
            throw new ConfigException("--worker-pods must be at least 1.");
        }
        try (Common.Session session = common.open(workerOptions)) {
            final StopSignal stop = new StopSignal();
            stop.installHandlers();
            WorkCommand.exitAfterGrace(stop, session.config());
            return sample(session.store(), session.config(), stop, workerPods);
        }
    }

    static int sample(final ObjectStore store, final RunConfig config, final StopSignal stop, final int workerPods)
            throws Exception {
        final InventorySummary inventory = new Gates(store, config).inventory().orElseThrow(
                () -> new ConfigException("Run " + config.runId() + " has no inventory yet: run the inventory first."));
        if (inventory.sampleShards() == 0) {
            LOGGER.info("Run {} has no sample.", config.runId());
            return ExitCodes.OK;
        }
        final SampleReporter reporter = new SampleReporter(store, config, Clock.systemUTC());
        if (reporter.existing().isPresent()) {
            LOGGER.info("The sample of run {} is done.", config.runId());
            return ExitCodes.OK;
        }
        final int work = WorkerFactory.create(store, config, stop)
                .onlyShards(Set.copyOf(SampleReporter.sampleShards(inventory))).run(true);
        if (work != ExitCodes.OK) {
            return work;
        }
        return reporter.write(inventory, config.worker().threads(), workerPods);
    }

}
