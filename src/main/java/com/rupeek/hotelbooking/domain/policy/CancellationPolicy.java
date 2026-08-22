package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.model.Booking;
import java.time.Instant;

/**
 * Decides what a guest gets back when they cancel.
 *
 * <p>Each policy is a self-contained rule identified by a {@link #code()}, and a property stores
 * only that code. Two consequences worth stating explicitly:
 *
 * <ul>
 *   <li>Adding a policy — a promotional "free cancellation until check-in", a seasonal variant — is
 *       one new class plus one bean registration. No enum grows, no {@code switch} is edited, and
 *       {@code CancellationService} does not know how many policies exist.
 *   <li>Different properties in the same chain can run different policies, because the choice is
 *       data on the property rather than a global setting.
 * </ul>
 *
 * <p>{@code now} is a parameter rather than a call to {@code Instant.now()} inside the method. That
 * single decision is what makes the time-boundary behaviour of every policy testable without
 * sleeping, mocking statics, or waiting a day.
 */
public interface CancellationPolicy {

    /** Stable identifier persisted on the property. Changing it is a data migration. */
    String code();

    /** Human-readable summary, surfaced in search results so guests can compare terms. */
    String description();

    RefundDecision evaluate(Booking booking, Instant now);
}
