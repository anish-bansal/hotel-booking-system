package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import com.rupeek.hotelbooking.domain.port.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepositoryAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpa;

    PaymentRepositoryAdapter(PaymentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Payment save(Payment payment) {
        return jpa.save(payment);
    }

    @Override
    public Payment saveAndClaimIdempotencyKey(Payment payment) {
        // saveAndFlush, not save: the INSERT must reach the database now, so the unique constraint
        // can reject a concurrent duplicate before the gateway is called. See the port's javadoc.
        return jpa.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jpa.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<Payment> findByBookingId(UUID bookingId) {
        return jpa.findByBookingIdOrderByCreatedAtDesc(bookingId);
    }

    @Override
    public Optional<Payment> findSuccessfulByBookingId(UUID bookingId) {
        return jpa.findByBookingIdAndStatus(bookingId, PaymentStatus.SUCCESSFUL);
    }
}
