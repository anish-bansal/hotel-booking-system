package com.rupeek.hotelbooking.domain.exception;

/** The gateway declined the charge. Maps to HTTP 402. */
public class PaymentFailedException extends DomainException {

    public PaymentFailedException(String reason) {
        super("Payment failed: " + reason);
    }
}
