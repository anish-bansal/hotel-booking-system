package com.rupeek.hotelbooking.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests move by hand.
 *
 * <p>This is what makes time-dependent behaviour testable in milliseconds instead of in real hours.
 * Hold expiry, the 24-hour refund boundary and the seven-day tier are all assertions about elapsed
 * time; with the system clock they would be untestable, flaky, or both. The production code takes a
 * {@code Clock} in its constructor for exactly this reason.
 */
public class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advanceBy(Duration amount) {
        now = now.plus(amount);
    }

    public void setTo(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
