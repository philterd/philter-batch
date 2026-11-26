/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Decision;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.store.ObjectStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** Records a decision at a gate: {@code approve} or {@code reject}. */
public abstract class DecideCommand implements Callable<Integer> {

    @Mixin
    Console console;

    @Parameters(arity = "1..2", paramLabel = "RUN_ID GATE", description = {"The run and the gate (sample or release).",
            "With -c, the gate only."})
    List<String> arguments;

    @Option(names = {"-c", "--config"}, description = "A local run configuration instead of a run in the deployment.")
    Path config;

    @Option(names = "--by", description = "Who decided, for the record.")
    String by;

    @Option(names = "--note", description = "Why, for the record.")
    String note;

    abstract Decision decision();

    @Override
    public Integer call() {
        if (arguments.size() != (config == null ? 2 : 1)) {
            throw new ConfigException(config == null ? "Give the run ID and the gate, such as: pilot-2026-11 sample."
                    : "With -c, give the gate only, such as: sample.");
        }
        final Gate which = Gate.parse(arguments.getLast());
        final RunConfig run;
        final ObjectStore store;
        if (config != null) {
            run = ConfigLoader.load(config);
            store = Common.store(run.store());
        } else {
            final Deployment d = console.deployment();
            store = console.store(d);
            try {
                run = Common.stored(store, d, arguments.getFirst());
            } catch (final RuntimeException e) {
                store.close();
                throw e;
            }
        }
        try (store) {
            final boolean recorded = new Gates(store, run).decide(which, decision(), by, note, Console.clock);
            Console.out.println((recorded ? "Recorded: " : "Already recorded: ") + "the " + which.id() + " gate of run "
                    + run.runId() + " is " + decision().id() + ".");
        }
        return ExitCodes.OK;
    }

    @Command(name = "approve", mixinStandardHelpOptions = true,
            description = {"Approve a run's sample or release.",
                    "The sample after its report is written, the release after finalize. Writes",
                    "gates/<gate>-approved.json under the run's prefix; the workflow continues within a poll."})
    public static class Approve extends DecideCommand {
        @Override
        Decision decision() {
            return Decision.APPROVED;
        }
    }

    @Command(name = "reject", mixinStandardHelpOptions = true,
            description = {"Reject a run's sample or release. Writes gates/<gate>-rejected.json; the workflow ends",
                    "without running the steps past the gate. A rejected run cannot be resumed."})
    public static class Reject extends DecideCommand {
        @Override
        Decision decision() {
            return Decision.REJECTED;
        }
    }

}
