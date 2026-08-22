package com.rupeek.hotelbooking.domain.exception;

import java.time.LocalDate;

/**
 * The requested rooms are not available for at least one night of the stay.
 *
 * <p>Carries the offending date because "not available" without saying <em>when</em> is useless to
 * a caller trying to suggest alternatives. Maps to HTTP 409.
 */
public class InventoryUnavailableException extends DomainException {

    private final LocalDate firstUnavailableNight;

    public InventoryUnavailableException(LocalDate firstUnavailableNight, int requested, int available) {
        super("Only " + available + " room(s) available on " + firstUnavailableNight
                + " but " + requested + " requested");
        this.firstUnavailableNight = firstUnavailableNight;
    }

    public LocalDate firstUnavailableNight() {
        return firstUnavailableNight;
    }
}
