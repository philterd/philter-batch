/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.coordination.StoreClock;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.batch.store.PreconditionFailedException;
import ai.philterd.batch.store.PutResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "check-store", mixinStandardHelpOptions = true,
        description = {"Check that the store can coordinate a run.",
                "It must enforce the conditional writes the coordination depends on, and this machine's clock must",
                "be close to the store's. Writes and deletes test objects under the run's prefix."})
public class CheckStoreCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(CheckStoreCommand.class);

    @Mixin
    Common common;

    @Override
    public Integer call() {
        try (Common.Session session = common.open()) {
            return check(session.store(), session.config(), Clock.systemUTC());
        }
    }

    static int check(final ObjectStore store, final RunConfig config, final Clock clock) {
        final RunLayout layout = new RunLayout(config);
        final String key = layout.root() + "check-store/" + UUID.randomUUID();
        final byte[] one = "1".getBytes(StandardCharsets.UTF_8);
        final byte[] two = "2".getBytes(StandardCharsets.UTF_8);
        boolean ok = true;
        try {
            final PutResult first = store.putIfAbsent(layout.bucket(), key, one, "text/plain");
            ok &= expectRefused("create-if-absent on an existing object",
                    () -> store.putIfAbsent(layout.bucket(), key, two, "text/plain"));
            ok &= expectRefused("write-if-match with a stale ETag", () -> store.putIfMatch(layout.bucket(), key, two,
                    "text/plain", "\"00000000000000000000000000000000\""));
            store.putIfMatch(layout.bucket(), key, two, "text/plain", first.etag());
            ok &= expectRefused("write-if-match on a missing object", () -> store.putIfMatch(layout.bucket(),
                    key + "-absent", two, "text/plain", first.etag()));
            final StoreClock storeClock = new StoreClock(store, layout.bucket(), clock, Duration.ofDays(3650));
            storeClock.sync();
            LOGGER.info("This machine's clock is {} seconds behind the store's (lease.max_clock_skew_seconds is {}).",
                    storeClock.offset().toSeconds(), config.lease().maxClockSkewSeconds());
            if (storeClock.offset().abs().toSeconds() > config.lease().maxClockSkewSeconds()) {
                LOGGER.error("The clock difference is over the limit; workers here would refuse to start.");
                ok = false;
            }
        } finally {
            store.delete(layout.bucket(), key);
        }
        LOGGER.info(ok ? "The store supports Philter Batch's coordination." : "The store cannot coordinate Philter Batch.");
        return ok ? ExitCodes.OK : ExitCodes.ERROR;
    }

    private static boolean expectRefused(final String what, final Runnable write) {
        try {
            write.run();
            LOGGER.error("The store accepted {}; it must refuse it with 412.", what);
            return false;
        } catch (final PreconditionFailedException e) {
            LOGGER.info("The store refused {}, as required.", what);
            return true;
        }
    }

}
