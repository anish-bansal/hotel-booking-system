package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BookingJpaRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByGuestEmailOrderByCreatedAtDesc(String guestEmail);

    @Query("""
            select b from Booking b
            where b.status = :status
              and b.holdExpiresAt < :asOf
            """)
    List<Booking> findLapsedHolds(@Param("status") BookingStatus status,
                                 @Param("asOf") Instant asOf);
}
