/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Set when the process is asked to stop (SIGTERM, as when a spot node is reclaimed). Workers finish the file in
 * hand, save progress, release their shard, and exit with {@code ExitCodes.STOPPED}.
 */
public final class StopSignal {

    private static final Logger LOGGER = LogManager.getLogger(StopSignal.class);

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch latch = new CountDownLatch(1);

    public boolean stopped() {
        return stopped.get();
    }

    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            LOGGER.info("Stop requested: finishing the current files and saving progress.");
            latch.countDown();
        }
    }

    /** Waits up to the duration, returning early if stopped. */
    public void await(final Duration duration) throws InterruptedException {
        latch.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Handles SIGTERM and SIGINT by setting the signal instead of exiting, so the worker can save progress and exit
     * with its own code. Uses {@code sun.misc.Signal}; where that is unavailable, the JVM's default handling applies
     * and the next worker resumes from the last saved cursor once the lease expires.
     */
    public void installHandlers() {
        for (final String name : new String[] {"TERM", "INT"}) {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal(name), signal -> stop());
            } catch (final IllegalArgumentException | UnsupportedOperationException | LinkageError e) {
                LOGGER.warn("Could not handle SIG{}: {}", name, e.getClass().getSimpleName());
            }
        }
    }

}
