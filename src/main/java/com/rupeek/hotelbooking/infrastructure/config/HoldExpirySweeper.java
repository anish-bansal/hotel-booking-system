package com.rupeek.hotelbooking.infrastructure.config;

import com.rupeek.hotelbooking.application.BookingService;
import com.rupeek.hotelbooking.infrastructure.lock.SweepLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns rooms to sale when a guest starts a booking and never pays.
 *
 * <p>Without this, the decision to hold inventory before taking payment would be a slow leak: every
 * abandoned checkout would remove rooms from sale permanently. The sweeper is what makes
 * "hold first, charge second" safe rather than merely optimistic.
 *
 * <p>It stays the thinnest possible class — a schedule, a lock, and a delegation. All the logic lives
 * in {@link BookingService#expireLapsedHolds()}, so it is testable by calling a method directly
 * rather than by waiting for a timer. A scheduled job that contains its own business rules is a
 * scheduled job nobody can test.
 *
 * <h2>Running more than one instance</h2>
 *
 * Every instance would otherwise fire this on its own timer. That was always survivable — each
 * booking's own transition guard rejects a double expiry — but it meant N instances doing N times the
 * work and contending on the same inventory rows to discover there was nothing to do. The
 * {@link SweepLock} makes the work happen once: in-process by default, Redis-backed under the
 * {@code redis} profile, chosen by which bean exists rather than by an {@code if} in here.
 *
 * <p>The lease is derived from the sweep interval rather than hard-coded, so the lock cannot outlive
 * the gap between ticks and stall the next one.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
    private static final String LOCK_NAME = "hold-expiry-sweep";

    private final BookingService bookingService;
    private final SweepLock sweepLock;
    private final Duration leaseDuration;

    public HoldExpirySweeper(BookingService bookingService,
                             SweepLock sweepLock,
                             @Value("${hotel-booking.booking.hold-sweep-interval-ms:60000}")
                             long sweepIntervalMs) {
        this.bookingService = bookingService;
        this.sweepLock = sweepLock;
        // Two ticks' worth: long enough that a slow sweep keeps its lock, short enough that a
        // crashed instance does not block the next one for more than one missed cycle.
        this.leaseDuration = Duration.ofMillis(sweepIntervalMs * 2);
        log.info("Hold expiry sweeper active every {}ms, lock: {}", sweepIntervalMs,
                sweepLock.describe());
    }

    /**
     * {@code initialDelay} matches the interval so the first sweep does not fire the instant the
     * context starts. Sweeping at boot is pointless — nothing can have lapsed yet — and in tests it
     * would mean a scheduled job running while fixtures are still being built.
     */
    @Scheduled(
            fixedDelayString = "${hotel-booking.booking.hold-sweep-interval-ms:60000}",
            initialDelayString = "${hotel-booking.booking.hold-sweep-interval-ms:60000}")
    public void sweep() {
        if (!sweepLock.tryAcquire(LOCK_NAME, leaseDuration)) {
            log.debug("Another instance is sweeping; skipping this tick");
            return;
        }
        try {
            int expired = bookingService.expireLapsedHolds();
            if (expired > 0) {
                log.info("Hold sweeper expired {} unpaid booking(s)", expired);
            }
        } catch (RuntimeException e) {
            // Swallowed on purpose: an exception escaping a @Scheduled method with fixedDelay does
            // not stop the schedule, but it does log a stack trace with no context. Better to say
            // what failed and let the next sweep retry.
            log.error("Hold sweep failed; will retry on the next interval", e);
        } finally {
            // In a finally block because a sweep that throws must still give the lock back, or the
            // first failure would silently disable the sweeper until the lease expired.
            sweepLock.release(LOCK_NAME);
        }
    }
}
