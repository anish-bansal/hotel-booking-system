package com.rupeek.hotelbooking.domain.exception;

/**
 * An attempt to move an aggregate into a state its lifecycle does not permit — cancelling an
 * already-cancelled booking, paying for an expired one, and so on. Maps to HTTP 409.
 */
public class IllegalStateTransitionException extends DomainException {

    public IllegalStateTransitionException(String aggregate, Object from, Object to) {
        super(aggregate + " cannot move from " + from + " to " + to);
    }
}
