package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/**
 * Pay for a booking. The idempotency key travels in the {@code Idempotency-Key} header rather than
 * in this body, following the convention set by Stripe and others — it is a property of the request,
 * not of the payment being described.
 */
public record PaymentRequest(
        @NotNull PaymentMethod method,
        String payerReference) {
}
