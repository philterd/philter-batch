/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.coordination.ClaimResult;
import ai.philterd.batch.coordination.ClockSkewException;
import ai.philterd.batch.coordination.Coordinator;
import ai.philterd.batch.coordination.HeldShard;
import ai.philterd.batch.coordination.LeaseLostException;
import ai.philterd.batch.coordination.StoreClock;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.redaction.TextRedactor;
import ai.philterd.batch.run.FormatException;
import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A worker process: {@code worker.threads} threads, each claiming and processing shards until none is left, and one
 * thread renewing every held lease. In a pod (Argo), it exits when no unclaimed shard is left; in the single-process
 * pilot, it can instead wait for other holders' leases to expire.
 */
public final class Worker {

    private static final Logger LOGGER = LogManager.getLogger(Worker.class);

    /** Consecutive unexpected failures after which a thread gives up. */
    static final int MAX_CONSECUTIVE_ERRORS = 3;

    /** The longest sleep while waiting for leases to expire, so a stop is noticed promptly. */
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private enum ThreadResult { NO_WORK, ALL_LEASED, STOPPED, ERROR }

    private final ObjectStore store;
    private final RunConfig config;
    private final RunLayout layout;
    private final TextRedactor redactor;
    private final Clock localClock;
    private final StopSignal stop;
    private final String processId;
    private final List<Formats.ModelInfo> models;
    private final Set<HeldShard> held = ConcurrentHashMap.newKeySet();
    private Set<String> only;

    /**
     * @param models The models the redactor runs, recorded in each done marker.
     */
    public Worker(final ObjectStore store, final RunConfig config, final TextRedactor redactor,
                  final List<Formats.ModelInfo> models, final Clock localClock, final StopSignal stop,
                  final String processId) {
        this.store = store;
        this.config = config;
        this.layout = new RunLayout(config);
        this.redactor = redactor;
        this.localClock = localClock;
        this.stop = stop;
        this.processId = processId;
        this.models = List.copyOf(models);
    }

    /**
     * Restricts this worker to the given shards, such as the sample's, and records where each replacement is in
     * their results, for the sample report.
     */
    public Worker onlyShards(final Set<String> shards) {
        this.only = Set.copyOf(shards);
        return this;
    }

    /** A process ID for worker names: the host name (the pod name in Kubernetes) and a random suffix. */
    public static String defaultProcessId() {
        final String host = System.getenv().getOrDefault("HOSTNAME", "local");
        final String clean = host.replaceAll("[^A-Za-z0-9-]", "-");
        return clean.substring(0, Math.min(clean.length(), 40)) + "-"
                + Integer.toHexString(new SecureRandom().nextInt(1 << 24) | (1 << 24)).substring(1);
    }

    /** The run's recorded settings, checked against this worker's configuration. */
    public static RunSettings checkSettings(final ObjectStore store, final RunConfig config) {
        final RunLayout layout = new RunLayout(config);
        final RunSettings recorded = Json.read(store.get(layout.bucket(), layout.settings())
                .orElseThrow(() -> new ConfigException("Run " + config.runId() + " has no inventory yet: run the "
                        + "inventory first.")).data(), RunSettings.class);
        FormatException.check("run.json", recorded.format(), Formats.FORMAT);
        final RunSettings mine = Inventory.settings(config, recorded.policySha256());
        if (!mine.equals(recorded)) {
            throw new ConfigException("This worker's configuration differs from the one run " + config.runId()
                    + " was inventoried with (output mode, charset, or extensions).");
        }
        if (store.get(layout.bucket(), layout.inventory()).isEmpty()) {
            throw new ConfigException("Run " + config.runId() + "'s inventory has not finished.");
        }
        return recorded;
    }

    /**
     * Processes shards until none is left to claim.
     * @param waitForLeases Wait for other holders' leases to expire instead of exiting with
     *                      {@link ExitCodes#ALL_LEASED}.
     * @return An exit code from {@link ExitCodes}.
     */
    public int run(final boolean waitForLeases) {
        final RunSettings settings = checkSettings(store, config);
        final StoreClock clock = new StoreClock(store, layout.bucket(), localClock,
                Duration.ofSeconds(config.lease().maxClockSkewSeconds()));
        clock.sync();
        final Coordinator coordinator = new Coordinator(store, layout, config.lease(), clock, new SecureRandom(), only);

        final int threads = config.worker().threads();
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final ExecutorService redactions = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "redaction");
            t.setDaemon(true);
            return t;
        });
        final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "lease-renewer");
            t.setDaemon(true);
            return t;
        });
        final long renew = config.lease().renewSeconds();
        renewer.scheduleWithFixedDelay(() -> renewAll(coordinator), renew, renew, TimeUnit.SECONDS);

        final List<Future<ThreadResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String name = processId + "-t" + i;
            final DocumentProcessor documents = new DocumentProcessor(store, config, layout, redactor,
                    settings.policySha256(), name, redactions, only != null);
            final ShardProcessor shards = new ShardProcessor(store, config, layout, coordinator, documents, stop, name,
                    settings.policySha256(), models, localClock);
            futures.add(pool.submit(() -> loop(name, coordinator, shards, clock, waitForLeases)));
        }
        pool.shutdown();

        final List<ThreadResult> results = new ArrayList<>();
        try {
            for (final Future<ThreadResult> future : futures) {
                results.add(future.get());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExitCodes.ERROR;
        } catch (final java.util.concurrent.ExecutionException e) {
            LOGGER.error("A worker thread failed: {}", e.getCause().getClass().getName());
            results.add(ThreadResult.ERROR);
        } finally {
            renewer.shutdownNow();
            redactions.shutdownNow();
        }
        if (results.contains(ThreadResult.STOPPED)) {
            return ExitCodes.STOPPED;
        }
        if (results.contains(ThreadResult.ERROR)) {
            return ExitCodes.ERROR;
        }
        // A thread that found only its siblings' shards left reports them as leased; whether work remains is
        // decided by the done markers once every thread has finished.
        if (results.contains(ThreadResult.ALL_LEASED)
                && !coordinator.doneIds().containsAll(coordinator.shardIds())) {
            return ExitCodes.ALL_LEASED;
        }
        return ExitCodes.OK;
    }

    private ThreadResult loop(final String name, final Coordinator coordinator, final ShardProcessor shards,
                              final StoreClock clock, final boolean waitForLeases) {
        Thread.currentThread().setName(name);
        int errors = 0;
        while (!stop.stopped()) {
            final ClaimResult result;
            try {
                result = coordinator.claim(name);
            } catch (final ClockSkewException e) {
                LOGGER.error(e.getMessage());
                return ThreadResult.ERROR;
            } catch (final RuntimeException e) {
                LOGGER.warn("Looking for a shard failed: {}", describe(e));
                if (++errors >= MAX_CONSECUTIVE_ERRORS) {
                    return ThreadResult.ERROR;
                }
                continue;
            }
            switch (result) {
                case ClaimResult.NoWorkLeft n -> {
                    return ThreadResult.NO_WORK;
                }
                case ClaimResult.AllLeased leased -> {
                    if (!waitForLeases) {
                        LOGGER.info("{} shards remain, all claimed by other workers.", leased.remaining());
                        return ThreadResult.ALL_LEASED;
                    }
                    sleepUntil(clock, leased.nextExpiry());
                }
                case ClaimResult.Claimed claimed -> {
                    final HeldShard shard = claimed.shard();
                    held.add(shard);
                    try {
                        if (shards.process(shard) == ShardProcessor.Result.STOPPED) {
                            return ThreadResult.STOPPED;
                        }
                        errors = 0;
                    } catch (final LeaseLostException e) {
                        LOGGER.warn("Gave up shard {}: {}", shard.id(), e.getMessage());
                    } catch (final ClockSkewException e) {
                        LOGGER.error(e.getMessage());
                        return ThreadResult.ERROR;
                    } catch (final RuntimeException e) {
                        // A store outage or similar: leave the claim to expire, so another lease retries the shard.
                        LOGGER.warn("Processing shard {} failed: {}", shard.id(), describe(e));
                        if (++errors >= MAX_CONSECUTIVE_ERRORS) {
                            return ThreadResult.ERROR;
                        }
                    } finally {
                        held.remove(shard);
                    }
                }
            }
        }
        return ThreadResult.STOPPED;
    }

    private void sleepUntil(final StoreClock clock, final Instant expiry) {
        Duration wait = expiry == null ? MAX_WAIT
                : Duration.between(clock.now(), expiry.plusSeconds(config.lease().expiryGraceSeconds() + 1));
        if (wait.isNegative() || wait.isZero()) {
            wait = Duration.ofMillis(100);
        }
        if (wait.compareTo(MAX_WAIT) > 0) {
            wait = MAX_WAIT;
        }
        try {
            Thread.sleep(wait);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            stop.stop();
        }
    }

    private void renewAll(final Coordinator coordinator) {
        for (final HeldShard shard : held) {
            try {
                coordinator.renew(shard);
            } catch (final LeaseLostException e) {
                LOGGER.warn("Lost the lease on shard {}.", shard.id());
            } catch (final RuntimeException e) {
                // Retried on the next tick; checkHeld stops writes if the lease runs out first.
                LOGGER.warn("Renewing the lease on shard {} failed: {}", shard.id(), describe(e));
            }
        }
    }

    /** An exception's class and, for this project's own exceptions, its message (which carries no content). */
    static String describe(final Throwable e) {
        final String name = e.getClass().getName();
        return name.startsWith("ai.philterd.batch.") ? name + ": " + e.getMessage() : name;
    }

}
