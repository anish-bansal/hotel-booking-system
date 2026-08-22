package com.rupeek.hotelbooking.application.command;

import java.time.LocalDate;
import java.util.UUID;

/** Request rooms for a stay. Produces a booking holding inventory, awaiting payment. */
public record CreateBookingCommand(
        UUID propertyId,
        UUID roomTypeId,
        String guestName,
        String guestEmail,
        LocalDate checkIn,
        LocalDate checkOut,
        int guests,
        int rooms) {
}
