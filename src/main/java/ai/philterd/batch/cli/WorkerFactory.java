/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.redaction.PhileasRedactor;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.worker.StopSignal;
import ai.philterd.batch.worker.Worker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.time.Clock;

/** Builds a worker that redacts with the policy stored with the run. */
final class WorkerFactory {

    private static final Logger LOGGER = LogManager.getLogger(WorkerFactory.class);

    private WorkerFactory() {
    }

    static Worker create(final ObjectStore store, final RunConfig config, final StopSignal stop) throws Exception {
        final RunSettings settings = Worker.checkSettings(store, config);
        final RunLayout layout = new RunLayout(config);
        final byte[] policyJson = store.get(layout.bucket(), layout.policy())
                .orElseThrow(() -> new ConfigException("Run " + config.runId() + " has no stored policy.")).data();
        final LoadedPolicy policy = LoadedPolicy.load(policyJson, Path.of(config.modelsDirectory()));
        if (!policy.sha256().equals(settings.policySha256())) {
            throw new ConfigException("The run's stored policy does not match its recorded hash.");
        }
        // Read by phileas-pheye-onnx when it loads a model.
        System.setProperty("pheye.onnx.threads", Integer.toString(config.worker().onnxThreads()));
        final String leftover = leftoverCheck(policy);
        final PhileasRedactor redactor = new PhileasRedactor(policy,
                LoadedPolicy.phileasConfiguration(config.policyFilesDirectories()));
        LOGGER.info("Loaded policy sha256:{} with {} model(s); output mode {}.",
                policy.sha256(), policy.models().size(), config.output().mode().json());
        if (!"off".equals(leftover)) {
            LOGGER.info("The policy's leftover check ({}) is ignored: Philter Batch does not run it for now.", leftover);
        }
        return new Worker(store, config, redactor, policy.models(), Clock.systemUTC(), stop,
                Worker.defaultProcessId());
    }

    /** The policy's leftover check, as {@code mode/using}, or {@code off}. */
    static String leftoverCheck(final LoadedPolicy policy) {
        final var config = policy.policy().getConfig();
        final var check = config == null || config.getReview() == null ? null : config.getReview().getLeftoverCheck();
        if (check == null || check.getMode() == null
                || check.getMode() == ai.philterd.phileas.policy.config.LeftoverCheck.Mode.OFF) {
            return "off";
        }
        return check.getMode().getValue() + "/" + (check.getUsing() == null ? "broad" : check.getUsing().getValue());
    }

}
