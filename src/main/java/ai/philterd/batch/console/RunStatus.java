/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.console;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.Deployment;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Decision;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.run.Formats.GateDecision;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.SampleReport;
import ai.philterd.batch.run.Hashes;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.StoredObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a run is, read from its objects in the bucket and from its Argo workflows, and whether it waits on a person.
 * @param stage Where the run is; see {@link Stage}.
 * @param waitingOnPerson Whether nothing moves until a person acts.
 * @param next What to do next, as a command where there is one.
 */
public record RunStatus(String runId, Stage stage, boolean waitingOnPerson, String next, String configError,
                        String policySha256, Workflows.Info workflow, InventorySummary inventory, int shards,
                        int shardsDone, int sampleShardsDone, boolean sampleReport, Gate gateWaiting,
                        GateDecision sampleDecision, String sampleGate, boolean finalized, String releaseGate,
                        GateDecision releaseDecision) {

    /** Where a run is. */
    public enum Stage {
        NOT_CONFIGURED("not configured"),
        CONFIGURATION_INVALID("configuration not valid"),
        NO_POLICY("configured, without a policy"),
        READY("configured, not started"),
        INVENTORY("taking the inventory"),
        SAMPLE("redacting the sample"),
        SAMPLE_WAITING("waiting for a decision on the sample"),
        SAMPLE_REJECTED("ended: the sample was rejected"),
        WORKING("redacting"),
        FINALIZING("finalizing"),
        RELEASE_WAITING("waiting for a decision on the release"),
        RELEASE_REJECTED("ended: the release was rejected"),
        FINISHED("finished");

        private final String text;

        Stage(final String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        /** Whether the run has ended, so there is nothing to submit. */
        public boolean ended() {
            return this == SAMPLE_REJECTED || this == RELEASE_REJECTED || this == FINISHED;
        }
    }

    /** Reads a run's status. {@code workflows} may be null outside a cluster. */
    public static RunStatus read(final ObjectStore store, final Deployment deployment, final String runId,
                                 final Workflows workflows) {
        if (!ConfigLoader.RUN_ID.matcher(runId).matches()) {
            throw new ConfigException("A run ID is letters, digits, '.', '_', and '-', up to 64 characters.");
        }
        final RunLayout layout = new RunLayout(deployment.output().bucket(), deployment.output().prefix(), runId);
        final List<Workflows.Info> runs = workflows == null ? List.of() : workflows.list(runId);
        final Workflows.Info workflow = runs.isEmpty() ? null : runs.getFirst();
        final boolean running = workflow != null && workflow.active();

        final Optional<StoredObject> yaml = store.get(layout.bucket(), layout.storedConfig());
        if (yaml.isEmpty()) {
            return simple(runId, Stage.NOT_CONFIGURED, true, "philter-batch configure --stdin < run.yaml", null,
                    workflow);
        }
        final RunConfig config;
        try {
            config = ConfigLoader.parseStored(new String(yaml.get().data(), StandardCharsets.UTF_8), deployment);
        } catch (final ConfigException e) {
            return simple(runId, Stage.CONFIGURATION_INVALID, true, "philter-batch configure --stdin < run.yaml",
                    e.getMessage(), workflow);
        }
        final Optional<StoredObject> policy = store.get(layout.bucket(), layout.storedPolicy());
        final String policySha = policy.map(p -> Hashes.sha256(p.data())).orElse(null);
        if (policy.isEmpty()) {
            return simple(runId, Stage.NO_POLICY, true, "philter-batch set-policy " + runId + " --stdin < policy.json",
                    null, workflow);
        }

        final Gates gates = new Gates(store, config);
        final Optional<InventorySummary> inventory = gates.inventory();
        final Set<String> shardIds = ids(store, layout.bucket(), layout.shardsPrefix());
        final Set<String> done = ids(store, layout.bucket(), layout.donePrefix());
        done.retainAll(shardIds);
        final int sampleShards = inventory.map(InventorySummary::sampleShards).orElse(0);
        int sampleDone = 0;
        for (int i = 0; i < sampleShards; i++) {
            if (done.contains(RunLayout.shardId(i))) {
                sampleDone++;
            }
        }
        final boolean report = store.get(layout.bucket(), layout.sampleReport()).isPresent();
        final boolean finalized = store.get(layout.bucket(), layout.success()).isPresent();
        final boolean sampleActive = gates.active(Gate.SAMPLE);
        final Optional<Decision> sample = sampleActive ? gates.decision(Gate.SAMPLE) : Optional.empty();
        final boolean releaseActive = gates.active(Gate.RELEASE);
        final Optional<Decision> release = releaseActive ? gates.decision(Gate.RELEASE) : Optional.empty();

        final Stage stage;
        final String next;
        if (inventory.isEmpty()) {
            stage = running || store.get(layout.bucket(), layout.settings()).isPresent() ? Stage.INVENTORY : Stage.READY;
            next = stage == Stage.READY ? "philter-batch submit " + runId : null;
        } else if (sample.orElse(null) == Decision.REJECTED) {
            stage = Stage.SAMPLE_REJECTED;
            next = null;
        } else if (sampleActive && !report) {
            stage = Stage.SAMPLE;
            next = null;
        } else if (sampleActive && sample.isEmpty()) {
            stage = Stage.SAMPLE_WAITING;
            next = "philter-batch sample-report " + runId + " > sample-report.html; then philter-batch approve "
                    + runId + " sample --by \"<name>\", or reject";
        } else if (!done.containsAll(shardIds)) {
            stage = Stage.WORKING;
            next = null;
        } else if (!finalized) {
            stage = Stage.FINALIZING;
            next = null;
        } else if (release.orElse(null) == Decision.REJECTED) {
            stage = Stage.RELEASE_REJECTED;
            next = null;
        } else if (releaseActive && release.isEmpty()) {
            stage = Stage.RELEASE_WAITING;
            next = "review report.json and the output; then philter-batch approve " + runId
                    + " release --by \"<name>\", or reject";
        } else {
            stage = Stage.FINISHED;
            next = null;
        }
        final boolean person = stage == Stage.SAMPLE_WAITING || stage == Stage.RELEASE_WAITING;
        final String resume = !person && !stage.ended() && stage != Stage.READY && !running
                ? "no workflow is running this run: philter-batch submit " + runId + " to resume it" : next;
        return new RunStatus(runId, stage, person, resume, null, policySha, workflow, inventory.orElse(null),
                shardIds.size(), done.size(), sampleDone, report,
                stage == Stage.SAMPLE_WAITING ? Gate.SAMPLE : stage == Stage.RELEASE_WAITING ? Gate.RELEASE : null,
                sampleActive ? gates.record(Gate.SAMPLE).orElse(null) : null,
                !sampleActive ? "none" : sample.map(Decision::id).orElse(report ? "waiting" : "not reached"), finalized,
                !releaseActive ? "none" : release.map(Decision::id).orElse(finalized ? "waiting" : "not reached"),
                releaseActive ? gates.record(Gate.RELEASE).orElse(null) : null);
    }

    private static RunStatus simple(final String runId, final Stage stage, final boolean person, final String next,
                                    final String error, final Workflows.Info workflow) {
        return new RunStatus(runId, stage, person, next, error, null, workflow, null, 0, 0, 0, false, null, null,
                null, false, null, null);
    }

    private static Set<String> ids(final ObjectStore store, final String bucket, final String prefix) {
        final Set<String> ids = new HashSet<>();
        for (final ObjectInfo o : store.list(bucket, prefix)) {
            ids.add(RunLayout.shardOf(o.key()));
        }
        return ids;
    }

    /** The sample report, if written. */
    public static Optional<SampleReport> sampleReport(final ObjectStore store, final RunLayout layout) {
        return store.get(layout.bucket(), layout.sampleReport()).map(o -> Json.read(o.data(), SampleReport.class));
    }

}
