/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.finalize.Finalizer;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.time.Clock;
import java.util.concurrent.Callable;

@Command(name = "finalize", mixinStandardHelpOptions = true,
        description = {"Write the manifest, report, and _SUCCESS once every shard is done.",
                "Exit codes: 0 finalized, 1 error, 5 not every shard is done, 6 finalized but the quarantined",
                "entries exceed quarantine.max_percent with no release gate (release_gate.enabled false)."})
public class FinalizeCommand implements Callable<Integer> {

    /**
     * Exit code 6 when the finalized run's quarantines exceed the limit and nothing else holds the output for a
     * person: no release gate ({@code gateHolds} false, as in {@code run}), or the gate is turned off.
     */
    static int quarantineCheck(final ObjectStore store, final RunConfig config, final boolean gateHolds) {
        final Gates gates = new Gates(store, config);
        if (gates.quarantineLimitExceeded() && !(gateHolds && gates.active(Gate.RELEASE))) {
            return ExitCodes.QUARANTINE_LIMIT;
        }
        return ExitCodes.OK;
    }

    @Mixin
    Common common;

    @Override
    public Integer call() {
        try (Common.Session session = common.open()) {
            final int code = new Finalizer(session.store(), session.config(), Clock.systemUTC()).run();
            return code == ExitCodes.OK ? quarantineCheck(session.store(), session.config(), true) : code;
        }
    }

}
