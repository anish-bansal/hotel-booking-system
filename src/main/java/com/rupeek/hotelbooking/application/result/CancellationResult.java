package com.rupeek.hotelbooking.application.result;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.policy.RefundDecision;

/** Outcome of a cancellation: the booking's new state plus the refund decision and its rationale. */
public record CancellationResult(
        Booking booking,
        RefundDecision refundDecision,
        String appliedPolicy,
        int roomsReleased) {
}
