package com.rupeek.hotelbooking.domain.exception;

/** Input that the domain refuses to accept. Maps to HTTP 400. */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super(message);
    }
}
