package com.rupeek.hotelbooking.application.command;

import java.util.UUID;

/** Cancel a booking, releasing its rooms and settling any refund the property's policy allows. */
public record CancelBookingCommand(UUID bookingId) {
}
