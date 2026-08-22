package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to move money for a booking.
 *
 * <p><b>Idempotency.</b> {@code idempotencyKey} is caller-supplied and carries a unique constraint.
 * That constraint — not application logic — is what makes a double-charge impossible: two
 * simultaneous requests carrying the same key both try to insert, the database lets exactly one
 * through, and the loser reads back the winner's record and returns it. A pre-insert "does this key
 * already exist?" check alone would leave a race window between the check and the insert; the
 * constraint closes it. Retrying a payment after a network timeout is therefore safe, which is the
 * whole point, because a timed-out charge is exactly the case where the client does not know
 * whether the money moved.
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_idempotency_key",
                columnNames = "idempotency_key"))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false)
    private PaymentMethod method;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false)),
            @AttributeOverride(name = "currencyCode", column = @Column(name = "currency", nullable = false))
    })
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    /** The mock gateway's transaction reference; a real one would be the PSP's id. */
    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "refunded_amount")),
            @AttributeOverride(name = "currencyCode", column = @Column(name = "refunded_currency"))
    })
    private Money refundedAmount;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID bookingId, String idempotencyKey, PaymentMethod method, Money amount,
                    Instant now) {
        this.bookingId = bookingId;
        this.idempotencyKey = idempotencyKey;
        this.method = method;
        this.amount = amount;
        this.status = PaymentStatus.INITIATED;
        this.createdAt = now;
    }

    public static Payment initiate(UUID bookingId, String idempotencyKey, PaymentMethod method,
                                   Money amount, Instant now) {
        if (bookingId == null) {
            throw new ValidationException("bookingId is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("idempotencyKey is required for every payment attempt");
        }
        if (method == null) {
            throw new ValidationException("payment method is required");
        }
        if (amount == null || amount.isZero()) {
            throw new ValidationException("payment amount must be positive");
        }
        return new Payment(bookingId, idempotencyKey.trim(), method, amount, now);
    }

    public void markSuccessful(String gatewayReference, Instant now) {
        transitionTo(PaymentStatus.SUCCESSFUL);
        this.gatewayReference = gatewayReference;
        this.settledAt = now;
    }

    public void markFailed(String reason, Instant now) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
        this.settledAt = now;
    }

    public void markRefunded(Money refundAmount, String gatewayReference, Instant now) {
        if (refundAmount == null || refundAmount.isGreaterThan(amount)) {
            throw new ValidationException("refund cannot exceed the amount paid (" + amount + ")");
        }
        transitionTo(PaymentStatus.REFUNDED);
        this.refundedAmount = refundAmount;
        this.gatewayReference = gatewayReference;
        this.settledAt = now;
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Payment", status, target);
        }
        this.status = target;
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.SUCCESSFUL;
    }

    public UUID id() {
        return id;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public PaymentMethod method() {
        return method;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String gatewayReference() {
        return gatewayReference;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant settledAt() {
        return settledAt;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }
}
