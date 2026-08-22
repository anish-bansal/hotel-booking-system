package com.rupeek.hotelbooking.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production {@code Clock} with one the tests control.
 *
 * <p>Note the fixed start instant. Tests that begin "now" are tests whose behaviour changes with the
 * calendar - a stay booked for next week passes in March and fails in December. Pinning the clock
 * makes every date in every test literal and permanent.
 */
@TestConfiguration
public class TestClockConfiguration {

    /** A Monday, deliberately mid-month and mid-year so nothing sits on an awkward boundary. */
    public static final Instant FIXED_NOW = Instant.parse("2026-03-02T09:00:00Z");

    public static LocalDate today() {
        return LocalDate.ofInstant(FIXED_NOW, ZoneOffset.UTC);
    }

    /**
     * Deliberately <em>not</em> {@code @Primary}. {@link MutableClock} extends {@link Clock}, so this
     * bean is itself a candidate for every {@code Clock} injection point. Marking it primary as well
     * as {@link #testClock} would leave two primaries among three candidates
     * ({@code mutableClock}, {@code testClock}, {@code clock}) and Spring would refuse to choose.
     *
     * <p>Tests that need to move time still inject it directly by its concrete type.
     */
    @Bean
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_NOW);
    }

    /**
     * Named {@code testClock}, not {@code clock}. {@code DomainConfiguration} already defines a bean
     * called {@code clock}, and Spring Boot rejects duplicate bean names by default rather than
     * silently letting one win — so a same-named bean here would fail the context at startup. A
     * different name plus a single {@code @Primary} gives an unambiguous winner.
     */
    @Bean
    @Primary
    public Clock testClock(MutableClock mutableClock) {
        return mutableClock;
    }
}
