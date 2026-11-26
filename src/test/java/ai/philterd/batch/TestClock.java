/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests set and advance. */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(final Instant now) {
        this.now = now;
    }

    public static TestClock at(final String instant) {
        return new TestClock(Instant.parse(instant));
    }

    public void advance(final Duration d) {
        now = now.plus(d);
    }

    public void set(final Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

}
