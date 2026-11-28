/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.keys.RunKeys;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.run.Formats.PolicyWarning;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

@Command(name = "inventory", mixinStandardHelpOptions = true,
        description = {"List the input, choose the sample, and write the run's shards.",
                "Does nothing if the inventory exists."})
public class InventoryCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(InventoryCommand.class);

    @Mixin
    Common common;

    @Override
    public Integer call() throws Exception {
        try (Common.Session session = common.open()) {
            inventory(session.store(), session.config(), session.policy());
            return ExitCodes.OK;
        }
    }

    static void inventory(final ObjectStore store, final RunConfig config, final byte[] policy) {
        // Refuse a policy the workers could not load, before any shard is written.
        final LoadedPolicy loaded = LoadedPolicy.load(policy, Path.of(config.modelsDirectory()));
        for (final PolicyWarning w : loaded.strategies().warnings()) {
            LOGGER.warn("The policy's {} uses {}: {}", w.where(), w.strategy(), w.message());
        }
        // Pins the secret version every process of the run then reads, and checks it can be read.
        final RunKeys keys = RunKeys.forInventory(store, config, loaded);
        if (keys != null) {
            LOGGER.info("The run's keys come from version {} of the pseudonymization secret.", keys.pin().versionId());
        }
        new Inventory(store, config, Clock.systemUTC()).run(policy, keys == null ? null : keys.pin());
    }

}
