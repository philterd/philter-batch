/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

import ai.philterd.batch.cli.AwaitGateCommand;
import ai.philterd.batch.cli.CheckStoreCommand;
import ai.philterd.batch.cli.ConfigureCommand;
import ai.philterd.batch.cli.DecideCommand;
import ai.philterd.batch.cli.FinalizeCommand;
import ai.philterd.batch.cli.FpeKeyCommand;
import ai.philterd.batch.cli.InventoryCommand;
import ai.philterd.batch.cli.RunCommand;
import ai.philterd.batch.cli.SampleCommand;
import ai.philterd.batch.cli.SampleReportCommand;
import ai.philterd.batch.cli.SetPolicyCommand;
import ai.philterd.batch.cli.StatusCommand;
import ai.philterd.batch.cli.SubmitCommand;
import ai.philterd.batch.cli.WorkCommand;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.coordination.ClockSkewException;
import ai.philterd.batch.redaction.PolicyException;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.run.Versions;
import ai.philterd.batch.store.ObjectStoreException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Philter Batch: redacts documents in object storage with Phileas in-process, coordinated through the store.
 */
@Command(name = "philter-batch", mixinStandardHelpOptions = true, versionProvider = PhilterBatch.Version.class,
        description = "Batch redaction of documents in object storage with Phileas. Needs no Philter server.",
        subcommands = {ConfigureCommand.class, SetPolicyCommand.class, SubmitCommand.class, StatusCommand.class,
                DecideCommand.Approve.class, DecideCommand.Reject.class, SampleReportCommand.class, FpeKeyCommand.class,
                CheckStoreCommand.class,
                InventoryCommand.class, SampleCommand.class, AwaitGateCommand.class, WorkCommand.class,
                FinalizeCommand.class, RunCommand.class})
public final class PhilterBatch {

    private static final Logger LOGGER = LogManager.getLogger(PhilterBatch.class);

    public static void main(final String[] args) {
        System.exit(execute(args));
    }

    public static int execute(final String... args) {
        return new CommandLine(new PhilterBatch())
                .setExecutionExceptionHandler((e, cmd, parsed) -> {
                    if (e instanceof ConfigException || e instanceof PolicyException || e instanceof FormatException
                            || e instanceof ClockSkewException || e instanceof ObjectStoreException) {
                        LOGGER.error(e.getMessage());
                    } else {
                        // The class only: a message from a library could quote document text.
                        LOGGER.error("Failed: {}", e.getClass().getName());
                    }
                    return ExitCodes.ERROR;
                })
                .execute(args);
    }

    /** The version, from the build. */
    public static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"philter-batch " + Versions.philterBatch() + " (Phileas " + Versions.phileas()
                    + ", phileas-pheye-onnx " + Versions.pheyeOnnx() + ")"};
        }
    }

}
