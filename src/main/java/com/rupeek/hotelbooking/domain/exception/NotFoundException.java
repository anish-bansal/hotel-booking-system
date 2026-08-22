package com.rupeek.hotelbooking.domain.exception;

/** A referenced aggregate does not exist. Maps to HTTP 404. */
public class NotFoundException extends DomainException {

    public NotFoundException(String type, Object id) {
        super(type + " not found: " + id);
    }
}
