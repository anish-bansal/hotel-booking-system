package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * A booking as the API reports it.
 *
 * <p>{@code allowedNextStates} is included on purpose. A client that has to hard-code "a confirmed
 * booking can be cancelled" duplicates the state machine, and duplicated state machines drift. Here
 * the server tells the client what it may do next, straight from the same transition table the
 * domain enforces.
 */
public record BookingResponse(
        UUID id,
        UUID propertyId,
        UUID roomTypeId,
        String guestName,
        String guestEmail,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        int guests,
        int rooms,
        MoneyDto totalAmount,
        BookingStatus status,
        Set<BookingStatus> allowedNextStates,
        Instant createdAt,
        Instant holdExpiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        MoneyDto refundedAmount) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.id(),
                booking.propertyId(),
                booking.roomTypeId(),
                booking.guestName(),
                booking.guestEmail(),
                booking.stay().checkIn(),
                booking.stay().checkOut(),
                booking.stay().nightCount(),
                booking.guestCount(),
                booking.roomCount(),
                MoneyDto.from(booking.totalAmount()),
                booking.status(),
                booking.status().allowedNextStates(),
                booking.createdAt(),
                booking.holdExpiresAt(),
                booking.confirmedAt(),
                booking.cancelledAt(),
                MoneyDto.from(booking.refundedAmount()));
    }
}
