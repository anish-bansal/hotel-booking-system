package com.rupeek.hotelbooking.infrastructure.lock;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single-JVM lock. The default, and correct as long as exactly one instance is running.
 *
 * <p>It is a real lock, not a no-op: the scheduler could in principle overlap two sweeps if one ran
 * long, and this prevents that. What it cannot do is coordinate across processes — which is the
 * whole reason {@link RedisSweepLock} exists.
 *
 * <p>{@code @ConditionalOnMissingBean} is what makes the substitution automatic: declare a
 * {@link SweepLock} anywhere else and this one quietly steps aside. No profile check here, no
 * knowledge of Redis, no {@code if}.
 *
 * <p><b>Note the factory method is not named after the class.</b> Component scan already registers
 * this {@code @Configuration} class under the bean name {@code inProcessSweepLock} (its own name,
 * decapitalised). A {@code @Bean} method with that same name would be a second definition of an
 * existing name, and Spring Boot disables bean-definition overriding by default — the context would
 * refuse to start. Naming the method for what it produces, not for its holder, keeps the two names
 * distinct.
 */
@Configuration
public class InProcessSweepLock {

    @Bean
    @ConditionalOnMissingBean(SweepLock.class)
    public SweepLock sweepLock() {
        return new Impl();
    }

    static final class Impl implements SweepLock {

        private final Set<String> held = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryAcquire(String lockName, Duration leaseDuration) {
            // add() returns false if the name was already present - an atomic test-and-set.
            return held.add(lockName);
        }

        @Override
        public void release(String lockName) {
            held.remove(lockName);
        }

        @Override
        public String describe() {
            return "in-process (single instance only)";
        }
    }
}
