package com.rupeek.hotelbooking.infrastructure.lock;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cluster-wide lock backed by Redis, active under the {@code redis} profile.
 *
 * <p>This closes a gap the README used to just admit to: with more than one instance, every copy of
 * the hold-expiry sweeper would fire on its own timer. That was survivable — each booking's own
 * state machine rejects a double expiry — but it meant N instances doing N times the work and
 * contending on the same inventory rows for no reason.
 *
 * <h2>How the lock works</h2>
 *
 * {@code SET key <token> NX PX <ttl>} — a single atomic Redis command that sets the key only if it
 * does not exist and attaches an expiry in the same breath. Both halves matter:
 *
 * <ul>
 *   <li><b>NX</b> is the mutual exclusion. Exactly one instance's SET succeeds.
 *   <li><b>PX</b> is the safety valve. If the holder crashes mid-sweep it never releases, so without
 *       a TTL the sweeper would be wedged forever — the classic distributed-lock deadlock. The lease
 *       expires and the next tick proceeds.
 * </ul>
 *
 * <p>The value is a random token per acquisition, and {@link #release} deletes the key only if the
 * token still matches. Without that check, a slow holder whose lease had already expired could
 * delete a lock a <em>different</em> instance now legitimately owns.
 *
 * <h2>What this is not</h2>
 *
 * This is not Redlock, and it is not safe against arbitrary failure — a lease that expires while the
 * holder is still working means two instances sweep at once. That is acceptable here precisely
 * because the sweeper is idempotent: {@code Booking.expire()} rejects a second expiry, so the worst
 * case is wasted work rather than incorrect state. Being explicit about that boundary matters more
 * than pretending a 30-line lock is bulletproof; anything stronger belongs to a library
 * (ShedLock, Redisson) rather than to this file.
 */
@Configuration
@Profile("redis")
public class RedisSweepLock {

    @Bean
    public SweepLock redisSweepLock(StringRedisTemplate redis) {
        return new Impl(redis);
    }

    static final class Impl implements SweepLock {

        private static final Logger log = LoggerFactory.getLogger(Impl.class);
        private static final String KEY_PREFIX = "hotel-booking:lock:";

        private final StringRedisTemplate redis;
        /** Proves we still own what we are about to delete. */
        private final String ownerToken = UUID.randomUUID().toString();

        Impl(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public boolean tryAcquire(String lockName, Duration leaseDuration) {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(KEY_PREFIX + lockName, ownerToken, leaseDuration);
            return Boolean.TRUE.equals(acquired);
        }

        @Override
        public void release(String lockName) {
            String key = KEY_PREFIX + lockName;
            // Read-then-delete rather than a Lua compare-and-delete: a lost race here means we
            // decline to delete someone else's lock, which is the safe direction to fail in.
            String currentOwner = redis.opsForValue().get(key);
            if (ownerToken.equals(currentOwner)) {
                redis.delete(key);
            } else if (currentOwner != null) {
                log.warn("Not releasing lock '{}' - it is now held by another instance."
                        + " Our lease must have expired mid-sweep.", lockName);
            }
        }

        @Override
        public String describe() {
            return "Redis (cluster-safe, owner token " + ownerToken.substring(0, 8) + ")";
        }
    }
}
