package com.rupeek.hotelbooking.infrastructure.lock;

import java.time.Duration;

/**
 * A mutual exclusion primitive for scheduled work that must happen once, not once per instance.
 *
 * <p><b>Why this interface lives in {@code infrastructure} and not in {@code domain/port}.</b> A
 * distributed lock is not a business concept — no hotelier has an opinion about it, and no
 * requirement in the brief mentions it. It exists purely because we chose to run more than one copy
 * of the process. Putting it in {@code domain/port} alongside {@code BookingRepository} and
 * {@code PaymentGateway} would blur what that package means: those are contracts the <em>domain</em>
 * needs the outside world to satisfy. This is a deployment detail, so it belongs next to the
 * deployment details.
 *
 * <p>Two implementations, selected by profile:
 * <ul>
 *   <li>{@link InProcessSweepLock} — the default. Correct for a single instance and needs no
 *       infrastructure at all.
 *   <li>{@link RedisSweepLock} — active under the {@code redis} profile. Correct for a cluster.
 * </ul>
 *
 * <p>The default is deliberately the boring one. A prototype that requires Redis to start is a
 * prototype nobody runs.
 */
public interface SweepLock {

    /**
     * Try to take the lock without blocking.
     *
     * <p>Non-blocking on purpose. If another instance is already sweeping, the right behaviour is to
     * shrug and let the next scheduled tick come around — not to queue up a second sweep that will
     * find nothing to do. A blocking acquire here would convert a harmless race into a pile-up of
     * waiting threads.
     *
     * @param leaseDuration how long the lock survives if the holder dies without releasing it. This
     *                      is what stops a crashed instance from wedging the sweeper permanently.
     * @return true if the caller now holds the lock and must eventually {@link #release}
     */
    boolean tryAcquire(String lockName, Duration leaseDuration);

    void release(String lockName);

    /** What this implementation is, for the startup banner and the health endpoint. */
    String describe();
}
