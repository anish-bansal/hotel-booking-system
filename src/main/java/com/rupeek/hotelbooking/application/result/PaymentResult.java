package com.rupeek.hotelbooking.application.result;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.Payment;

/**
 * Outcome of a payment attempt.
 *
 * <p>{@code replayed} tells the caller that this response is the remembered outcome of an earlier
 * request carrying the same idempotency key, not a fresh charge. Surfacing that rather than hiding
 * it lets a client distinguish "your retry worked" from "your retry charged you again" — and makes
 * the idempotency guarantee observable, and therefore testable, instead of merely claimed.
 */
public record PaymentResult(Payment payment, Booking booking, boolean replayed) {

    public static PaymentResult processed(Payment payment, Booking booking) {
        return new PaymentResult(payment, booking, false);
    }

    public static PaymentResult replay(Payment payment, Booking booking) {
        return new PaymentResult(payment, booking, true);
    }
}
