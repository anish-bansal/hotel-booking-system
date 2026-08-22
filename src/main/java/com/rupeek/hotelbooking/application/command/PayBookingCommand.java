package com.rupeek.hotelbooking.application.command;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import java.util.UUID;

/**
 * Pay for a booking.
 *
 * <p>{@code idempotencyKey} is mandatory and supplied by the caller, because only the caller knows
 * that its second request is a retry of its first. A server-generated key would make every retry
 * look like a fresh charge, which is precisely the bug idempotency exists to prevent.
 */
public record PayBookingCommand(
        UUID bookingId,
        PaymentMethod method,
        String idempotencyKey,
        String payerReference) {
}
