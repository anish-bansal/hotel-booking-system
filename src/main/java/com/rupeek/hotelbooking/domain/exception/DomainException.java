package com.rupeek.hotelbooking.domain.exception;

/**
 * Root of the domain's own exception hierarchy.
 *
 * <p>Every failure the domain can express is a subclass of this, which lets the API layer map the
 * whole family to HTTP in one place without the domain knowing that HTTP exists.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
