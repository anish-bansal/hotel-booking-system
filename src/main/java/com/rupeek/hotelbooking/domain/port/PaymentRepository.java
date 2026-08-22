package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for payments. */
public interface PaymentRepository {

    Payment save(Payment payment);

    /**
     * Persist a new payment and make it visible to the database <em>immediately</em>, before this
     * method returns.
     *
     * <p>This exists for a correctness reason, not a performance one. The unique constraint on the
     * idempotency key is what prevents a double charge — but a constraint can only reject a row that
     * has actually reached the database. An ordinary {@code save} defers the insert until commit,
     * which is <em>after</em> the gateway has been called. Two concurrent requests carrying one key
     * would then both charge the card, and only one would survive to record it: money moved twice,
     * recorded once. That is the worst possible failure mode for this operation.
     *
     * <p>Claiming the key up front closes the window. The loser of the race fails here, before any
     * external side effect, and no money moves at all.
     */
    Payment saveAndClaimIdempotencyKey(Payment payment);

    Optional<Payment> findById(UUID id);

    /** The idempotency lookup: same key, same answer, no second charge. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByBookingId(UUID bookingId);

    Optional<Payment> findSuccessfulByBookingId(UUID bookingId);
}
