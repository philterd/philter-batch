/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Decision;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.worker.StopSignal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "await-gate", mixinStandardHelpOptions = true,
        description = {"Wait for a decision at a gate.",
                "Checks the run's gates/ in the bucket for the sample or release gate. Prints approved or rejected,",
                "and writes it to --decision-file. A run without the gate is approved.",
                "Exit codes: 0 decided (either way), 1 error, 3 stopped by a signal."})
public class AwaitGateCommand implements Callable<Integer> {

    @Mixin
    Common common;

    @Option(names = "--gate", required = true, description = "sample or release.")
    String gate;

    @Option(names = "--poll-seconds", defaultValue = "30", description = "How often to check (default: ${DEFAULT-VALUE}).")
    int pollSeconds;

    @Option(names = "--decision-file", description = "A file to write the decision to, for Argo's output parameter.")
    Path decisionFile;

    @Override
    public Integer call() throws IOException {
        final Gate which = Gate.parse(gate);
        try (Common.Session session = common.open()) {
            final StopSignal stop = new StopSignal();
            stop.installHandlers();
            final Optional<Decision> decision = new Gates(session.store(), session.config())
                    .await(which, Duration.ofSeconds(Math.max(1, pollSeconds)), stop);
            if (decision.isEmpty()) {
                return ExitCodes.STOPPED;
            }
            if (decisionFile != null) {
                Files.createDirectories(decisionFile.toAbsolutePath().getParent());
                Files.writeString(decisionFile, decision.get().id(), StandardCharsets.UTF_8);
            }
            Console.out.println(decision.get().id());
            return ExitCodes.OK;
        }
    }

}
