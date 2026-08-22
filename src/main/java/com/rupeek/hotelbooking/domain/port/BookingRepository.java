package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.Booking;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for bookings. */
public interface BookingRepository {

    Booking save(Booking booking);

    Optional<Booking> findById(UUID id);

    List<Booking> findByGuestEmail(String guestEmail);

    /** Unpaid bookings whose hold has lapsed, so the sweeper can return their rooms to sale. */
    List<Booking> findExpiredHolds(Instant asOf);
}
