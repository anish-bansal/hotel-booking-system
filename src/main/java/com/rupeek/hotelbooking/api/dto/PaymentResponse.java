package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.application.result.PaymentResult;
import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID bookingId,
        PaymentMethod method,
        MoneyDto amount,
        PaymentStatus status,
        String gatewayReference,
        String failureReason,
        Instant settledAt,
        /** True when this is the remembered outcome of an earlier request with the same key. */
        boolean idempotentReplay,
        BookingResponse booking) {

    public static PaymentResponse from(PaymentResult result) {
        Payment payment = result.payment();
        return new PaymentResponse(
                payment.id(),
                payment.bookingId(),
                payment.method(),
                MoneyDto.from(payment.amount()),
                payment.status(),
                payment.gatewayReference(),
                payment.failureReason(),
                payment.settledAt(),
                result.replayed(),
                BookingResponse.from(result.booking()));
    }
}
