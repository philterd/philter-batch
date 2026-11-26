/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.finalize.Finalizer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.time.Clock;
import java.util.concurrent.Callable;

@Command(name = "finalize", mixinStandardHelpOptions = true,
        description = {"Write the manifest, report, and _SUCCESS once every shard is done.",
                "Exit codes: 0 finalized, 1 error, 5 not every shard is done."})
public class FinalizeCommand implements Callable<Integer> {

    @Mixin
    Common common;

    @Override
    public Integer call() {
        try (Common.Session session = common.open()) {
            return new Finalizer(session.store(), session.config(), Clock.systemUTC()).run();
        }
    }

}
