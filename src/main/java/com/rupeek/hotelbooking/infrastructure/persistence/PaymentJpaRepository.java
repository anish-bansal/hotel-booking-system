package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    @Query("""
            select p from Payment p
            where p.bookingId = :bookingId
              and p.status = :status
            """)
    Optional<Payment> findByBookingIdAndStatus(@Param("bookingId") UUID bookingId,
                                               @Param("status") PaymentStatus status);
}
