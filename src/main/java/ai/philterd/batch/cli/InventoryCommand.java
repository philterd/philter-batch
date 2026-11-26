/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.redaction.LoadedPolicy;
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
        LoadedPolicy.load(policy, Path.of(config.modelsDirectory()));
        new Inventory(store, config, Clock.systemUTC()).run(policy);
    }

}
