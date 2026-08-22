package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.port.BookingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class BookingRepositoryAdapter implements BookingRepository {

    private final BookingJpaRepository jpa;

    BookingRepositoryAdapter(BookingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Booking save(Booking booking) {
        return jpa.save(booking);
    }

    @Override
    public Optional<Booking> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<Booking> findByGuestEmail(String guestEmail) {
        return jpa.findByGuestEmailOrderByCreatedAtDesc(guestEmail);
    }

    @Override
    public List<Booking> findExpiredHolds(Instant asOf) {
        // The status lives here rather than in the query string so a rename of the enum constant is
        // a compile error instead of a query that silently matches nothing.
        return jpa.findLapsedHolds(BookingStatus.PENDING_PAYMENT, asOf);
    }
}
