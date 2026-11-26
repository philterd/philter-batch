/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.coordination.Coordinator;
import ai.philterd.batch.coordination.HeldShard;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.DocumentResult;
import ai.philterd.batch.run.Formats.DoneMarker;
import ai.philterd.batch.run.Formats.Progress;
import ai.philterd.batch.run.Formats.ShardEntry;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.run.Versions;
import ai.philterd.batch.store.ObjectStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Processes one claimed shard from its cursor. JSONL records are buffered and uploaded as a part, followed by a
 * progress write naming the part, every {@code flush_bytes} or {@code flush_seconds}; after a holder died with the
 * cursor unmoved, progress is written after every entry instead, so a poison entry can be found exactly. At the end
 * the done marker is created if absent.
 */
public final class ShardProcessor {

    private static final Logger LOGGER = LogManager.getLogger(ShardProcessor.class);

    /** How processing a shard ended. */
    public enum Result { DONE, STOPPED }

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final Coordinator coordinator;
    private final EntryProcessor documents;
    private final StopSignal stop;
    private final String worker;
    private final String policySha256;
    private final Clock clock;
    private final List<Formats.ModelInfo> models;

    public ShardProcessor(final ObjectStore store, final RunConfig config, final RunLayout layout,
                          final Coordinator coordinator, final EntryProcessor documents, final StopSignal stop,
                          final String worker, final String policySha256, final List<Formats.ModelInfo> models,
                          final Clock clock) {
        this.store = store;
        this.config = config;
        this.layout = layout;
        this.coordinator = coordinator;
        this.documents = documents;
        this.stop = stop;
        this.worker = worker;
        this.policySha256 = policySha256;
        this.clock = clock;
        this.models = List.copyOf(models);
    }

    /**
     * Processes the shard.
     * @throws ai.philterd.batch.coordination.LeaseLostException When another worker took the shard; this worker
     *         must stop writing for it.
     */
    public Result process(final HeldShard held) {
        final Progress start = coordinator.fence(held);
        final List<ShardEntry> entries = held.shard().entries();
        final State state = new State(start);

        if (held.claim().stalledAttempts() >= config.lease().poisonAttempts() && state.cursor < entries.size()) {
            state.results.add(DocumentProcessor.poison(state.cursor, entries.get(state.cursor)));
            state.cursor++;
            save(held, state);
            coordinator.resetStall(held, state.cursor);
        }
        final boolean careful = held.claim().stalledAttempts() > 0;
        if (careful) {
            LOGGER.info("Shard {}: the last holder died at cursor {}; saving progress after every entry.",
                    held.id(), state.cursor);
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final List<DocumentResult> pending = new ArrayList<>();
        long lastFlush = clock.millis();
        while (state.cursor < entries.size() && !stop.stopped()) {
            coordinator.checkHeld(held);
            final DocumentProcessor.Outcome outcome = documents.process(state.cursor, entries.get(state.cursor));
            pending.add(outcome.result());
            if (outcome.record() != null) {
                buffer.writeBytes(outcome.record());
            }
            state.cursor++;
            if (careful || buffer.size() >= config.worker().flushBytes()
                    || clock.millis() - lastFlush >= config.worker().flushSeconds() * 1000L) {
                flush(held, state, buffer, pending);
                lastFlush = clock.millis();
            }
        }
        flush(held, state, buffer, pending);

        if (state.cursor < entries.size()) {
            coordinator.release(held);
            return Result.STOPPED;
        }
        coordinator.complete(held, new DoneMarker(Formats.FORMAT, config.runId(), held.id(), worker,
                held.claim().attempt(), clock.instant(), policySha256, Versions.phileas(), Versions.pheyeOnnx(),
                models, config.output().mode().json(), state.results, state.parts));
        return Result.DONE;
    }

    private static final class State {
        int cursor;
        long revision;
        final List<DocumentResult> results;
        final List<String> parts;
        final int attempt;
        final String shard;

        State(final Progress progress) {
            this.cursor = progress.cursor();
            this.revision = progress.revision();
            this.results = new ArrayList<>(progress.results());
            this.parts = new ArrayList<>(progress.parts());
            this.attempt = progress.attempt();
            this.shard = progress.shard();
        }
    }

    private void flush(final HeldShard held, final State state, final ByteArrayOutputStream buffer,
                       final List<DocumentResult> pending) {
        if (pending.isEmpty()) {
            return;
        }
        if (buffer.size() > 0) {
            // Named by this worker and its count of committed parts, so it never replaces a committed part.
            final String key = layout.part(held.id(), worker, state.parts.size());
            store.put(layout.bucket(), key, buffer.toByteArray(), "application/x-ndjson");
            state.parts.add(key);
            buffer.reset();
        }
        state.results.addAll(pending);
        pending.clear();
        save(held, state);
    }

    private void save(final HeldShard held, final State state) {
        state.revision++;
        coordinator.saveProgress(held, new Progress(Formats.FORMAT, state.shard, worker, state.attempt,
                state.revision, state.cursor, List.copyOf(state.results), List.copyOf(state.parts)));
    }

}
