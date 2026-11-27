/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.gate;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.GateDecision;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.Report;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Hashes;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.PreconditionFailedException;
import ai.philterd.batch.store.StoredObject;
import ai.philterd.batch.worker.StopSignal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The run's gates: points where the run waits for a person. A decision is a marker object under the run's
 * {@code gates/}, so it can be made from anywhere that can write to the bucket: with {@code philter-batch approve} or
 * {@code reject}, or by copying any object to the marker's key. A rejection wins over an approval if both exist.
 */
public final class Gates {

    private static final Logger LOGGER = LogManager.getLogger(Gates.class);

    /** A gate. */
    public enum Gate {
        /** After the sample, before the rest of the run. */
        SAMPLE,
        /** After finalize, before the output is released. */
        RELEASE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Gate parse(final String value) {
            for (final Gate g : values()) {
                if (g.id().equals(value)) {
                    return g;
                }
            }
            throw new ConfigException("Unknown gate '" + value + "': use sample or release.");
        }
    }

    /** A decision at a gate. */
    public enum Decision {
        APPROVED, REJECTED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;

    public Gates(final ObjectStore store, final RunConfig config) {
        this.store = store;
        this.config = config;
        this.layout = new RunLayout(config);
    }

    /** The run's inventory summary, if the inventory has finished. */
    public Optional<InventorySummary> inventory() {
        return store.get(layout.bucket(), layout.inventory()).map(o -> {
            final InventorySummary summary = Json.read(o.data(), InventorySummary.class);
            FormatException.check("inventory.json", summary.format(), Formats.FORMAT);
            return summary;
        });
    }

    /**
     * Whether the run has this gate. The sample gate exists when the inventory made a sample; the release gate when
     * the configuration turns it on (the default).
     */
    public boolean active(final Gate gate) {
        return switch (gate) {
            case SAMPLE -> inventory().map(i -> i.sampleShards() > 0).orElse(config.sample().enabled());
            case RELEASE -> config.releaseGate().enabled();
        };
    }

    /** The decision at a gate, if one has been made. */
    public Optional<Decision> decision(final Gate gate) {
        if (store.get(layout.bucket(), layout.gateMarker(gate.id(), Decision.REJECTED.id())).isPresent()) {
            return Optional.of(Decision.REJECTED);
        }
        if (store.get(layout.bucket(), layout.gateMarker(gate.id(), Decision.APPROVED.id())).isPresent()) {
            return Optional.of(Decision.APPROVED);
        }
        return Optional.empty();
    }

    /** The recorded decision, when the marker holds one in this format. */
    public Optional<GateDecision> record(final Gate gate) {
        final Optional<Decision> decision = decision(gate);
        if (decision.isEmpty()) {
            return Optional.empty();
        }
        final Optional<StoredObject> marker = store.get(layout.bucket(), layout.gateMarker(gate.id(),
                decision.get().id()));
        try {
            return marker.map(m -> Json.read(m.data(), GateDecision.class));
        } catch (final FormatException e) {
            // A marker written by hand; its presence is the decision.
            return Optional.empty();
        }
    }

    /**
     * Records a decision. Approving the sample needs its report; approving the release needs a finalized run.
     * @return False if the same decision was already recorded.
     * @throws ConfigException If the gate does not exist, is not ready to approve, or the other decision was made.
     */
    /** The run's {@code report.json}, once finalize has written it. */
    public Optional<Report> report() {
        return store.get(layout.bucket(), layout.report()).map(o -> Json.read(o.data(), Report.class));
    }

    /** Whether the finalized run's quarantined entries are above {@code quarantine.max_percent}. */
    public boolean quarantineLimitExceeded() {
        return report().map(r -> config.quarantine().exceeded(
                r.entriesByStatus().getOrDefault(DocumentResult.QUARANTINED, 0L), r.entries())).orElse(false);
    }

    public boolean decide(final Gate gate, final Decision decision, final String by, final String note,
                          final Clock clock) {
        return decide(gate, decision, by, note, false, clock);
    }

    /**
     * Records a decision. Approving a release whose quarantines exceed {@code quarantine.max_percent} needs
     * {@code acceptQuarantine}, which the decision then records.
     */
    public boolean decide(final Gate gate, final Decision decision, final String by, final String note,
                          final boolean acceptQuarantine, final Clock clock) {
        if (!active(gate)) {
            throw new ConfigException("Run " + config.runId() + " has no " + gate.id() + " gate"
                    + (gate == Gate.SAMPLE ? " (its sample is turned off)." : " (release_gate.enabled is false)."));
        }
        final Optional<Decision> existing = decision(gate);
        if (existing.isPresent()) {
            if (existing.get() == decision) {
                return false;
            }
            throw new ConfigException("The " + gate.id() + " gate of run " + config.runId() + " was already "
                    + existing.get().id() + ".");
        }
        String reportSha256 = null;
        if (gate == Gate.SAMPLE) {
            final Optional<StoredObject> html = store.get(layout.bucket(), layout.sampleReportHtml());
            if (decision == Decision.APPROVED && (html.isEmpty()
                    || store.get(layout.bucket(), layout.sampleReport()).isEmpty())) {
                throw new ConfigException("The sample report of run " + config.runId() + " is not ready; approve "
                        + "the sample after reviewing it.");
            }
            reportSha256 = html.map(h -> Hashes.sha256(h.data())).orElse(null);
        } else if (decision == Decision.APPROVED && store.get(layout.bucket(), layout.success()).isEmpty()) {
            throw new ConfigException("Run " + config.runId() + " has not been finalized; approve the release after "
                    + "reviewing its output.");
        }
        Boolean quarantineAccepted = null;
        if (gate == Gate.RELEASE && decision == Decision.APPROVED && quarantineLimitExceeded()) {
            if (!acceptQuarantine) {
                final Report r = report().orElseThrow();
                throw new ConfigException(String.format(Locale.ROOT, "Run %s quarantined %,d of %,d entries "
                        + "(%.2f%%), above quarantine.max_percent (%s%%). Review quarantined_by_reason in report.json "
                        + "and the manifest, then approve with --accept-quarantine, or reject.", config.runId(),
                        r.entriesByStatus().getOrDefault(DocumentResult.QUARANTINED, 0L), r.entries(),
                        RunConfig.Quarantine.percent(r.entriesByStatus().getOrDefault(DocumentResult.QUARANTINED,
                                0L), r.entries()), config.quarantine().maxPercent()));
            }
            quarantineAccepted = true;
        }
        final GateDecision record = new GateDecision(Formats.FORMAT, config.runId(), gate.id(), decision.id(),
                clock.instant(), by, note, reportSha256, quarantineAccepted);
        try {
            store.putIfAbsent(layout.bucket(), layout.gateMarker(gate.id(), decision.id()), Json.write(record),
                    "application/json");
        } catch (final PreconditionFailedException e) {
            return false;
        }
        LOGGER.info("The {} gate of run {} is {}.", gate.id(), config.runId(), decision.id());
        return true;
    }

    /**
     * Refuses to start the rest of the run until the sample is approved.
     * @throws ConfigException If the sample gate is waiting or was rejected.
     */
    public void requireSampleApproved() {
        if (!active(Gate.SAMPLE)) {
            return;
        }
        final Optional<Decision> decision = decision(Gate.SAMPLE);
        if (decision.isEmpty()) {
            throw new ConfigException("The sample of run " + config.runId() + " has not been approved: the rest of "
                    + "the run waits until it is (philter-batch approve " + config.runId() + " sample).");
        }
        if (decision.get() == Decision.REJECTED) {
            throw new ConfigException("The sample of run " + config.runId() + " was rejected: the run ends there. "
                    + "Start a new run (a new run_id) with a changed policy or configuration.");
        }
    }

    /**
     * Waits for a decision at the gate, checking the bucket every {@code poll}. A run without the gate is approved
     * at once.
     * @return The decision, or empty if stopped first.
     */
    public Optional<Decision> await(final Gate gate, final Duration poll, final StopSignal stop) {
        if (!active(gate)) {
            LOGGER.info("Run {} has no {} gate; continuing.", config.runId(), gate.id());
            return Optional.of(Decision.APPROVED);
        }
        boolean logged = false;
        while (!stop.stopped()) {
            final Optional<Decision> decision = decision(gate);
            if (decision.isPresent()) {
                LOGGER.info("The {} gate of run {} is {}.", gate.id(), config.runId(), decision.get().id());
                return decision;
            }
            if (!logged) {
                LOGGER.info("Waiting for a decision at the {} gate of run {}: philter-batch approve {} {}, or reject.",
                        gate.id(), config.runId(), config.runId(), gate.id());
                logged = true;
            }
            try {
                stop.await(poll);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

}
