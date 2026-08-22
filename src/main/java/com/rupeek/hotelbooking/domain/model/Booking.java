package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.DateRange;
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
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A guest's reservation of {@code rooms} rooms of one room type for a date range.
 *
 * <p><b>Where the lifecycle rules live.</b> Every state change goes through {@link #transitionTo},
 * which consults {@link BookingStatus}'s transition table and throws on anything illegal. There is
 * no setter for status, so it is not possible for a service, a controller, or a future contributor
 * to put a booking into a state the lifecycle forbids — the invariant is enforced by the type, not
 * by the discipline of callers. Cancelling twice, paying for an expired booking, or reviving a
 * cancelled one all fail the same way, in the same place.
 *
 * <p>Property and room type are referenced by id: a booking is its own aggregate with its own
 * lifecycle, and it should not be able to reach through and mutate the hotel's configuration.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Optimistic lock. {@code InventoryService} serialises contention on <em>rooms</em>, but nothing
     * serialised contention on the booking itself — and paying and cancelling touch no common row,
     * so they share no lock.
     *
     * <p>Two interleavings made that dangerous. Both requests read {@code PENDING_PAYMENT}, both pass
     * their transition guard, and the later write wins: a booking could end up {@code CONFIRMED}
     * after its rooms had been released. Worse, a cancellation could evaluate the refund before a
     * concurrent payment committed, correctly conclude "nothing was paid, nothing to refund", and
     * leave the guest charged, cancelled, and never refunded — with records that look perfectly
     * consistent afterwards.
     *
     * <p>A version column makes the second writer fail loudly instead of silently overwriting.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "property_id", nullable = false, updatable = false)
    private UUID propertyId;

    @Column(name = "room_type_id", nullable = false, updatable = false)
    private UUID roomTypeId;

    @Column(name = "guest_name", nullable = false)
    private String guestName;

    @Column(name = "guest_email", nullable = false)
    private String guestEmail;

    @Embedded
    private DateRange stay;

    @Column(name = "guest_count", nullable = false)
    private int guestCount;

    @Column(name = "room_count", nullable = false)
    private int roomCount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "total_amount", nullable = false)),
            @AttributeOverride(name = "currencyCode", column = @Column(name = "total_currency", nullable = false))
    })
    private Money totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When an unpaid booking stops holding inventory. Without this, an abandoned checkout would
     * sterilise saleable rooms forever.
     */
    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "refund_amount")),
            @AttributeOverride(name = "currencyCode", column = @Column(name = "refund_currency"))
    })
    private Money refundedAmount;

    protected Booking() {
        // for JPA
    }

    private Booking(UUID propertyId, UUID roomTypeId, String guestName, String guestEmail,
                    DateRange stay, int guestCount, int roomCount, Money totalAmount,
                    Instant now, Duration holdDuration) {
        this.propertyId = propertyId;
        this.roomTypeId = roomTypeId;
        this.guestName = guestName;
        this.guestEmail = guestEmail;
        this.stay = stay;
        this.guestCount = guestCount;
        this.roomCount = roomCount;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.holdExpiresAt = now.plus(holdDuration);
    }

    public static Booking request(UUID propertyId, UUID roomTypeId, String guestName, String guestEmail,
                                  DateRange stay, int guestCount, int roomCount, Money totalAmount,
                                  Instant now, Duration holdDuration) {
        if (propertyId == null || roomTypeId == null) {
            throw new ValidationException("propertyId and roomTypeId are required");
        }
        if (guestName == null || guestName.isBlank()) {
            throw new ValidationException("guestName is required");
        }
        if (guestEmail == null || !guestEmail.contains("@")) {
            throw new ValidationException("a valid guestEmail is required");
        }
        if (stay == null) {
            throw new ValidationException("stay dates are required");
        }
        if (guestCount < 1) {
            throw new ValidationException("guestCount must be at least 1 but was " + guestCount);
        }
        if (roomCount < 1) {
            throw new ValidationException("roomCount must be at least 1 but was " + roomCount);
        }
        if (totalAmount == null || totalAmount.isZero()) {
            throw new ValidationException("totalAmount must be a positive amount");
        }
        return new Booking(propertyId, roomTypeId, guestName.trim(), guestEmail.trim(), stay,
                guestCount, roomCount, totalAmount, now, holdDuration);
    }

    public void confirm(Instant now) {
        transitionTo(BookingStatus.CONFIRMED);
        this.confirmedAt = now;
    }

    public void cancel(Instant now) {
        transitionTo(BookingStatus.CANCELLED);
        this.cancelledAt = now;
    }

    public void expire() {
        transitionTo(BookingStatus.EXPIRED);
    }

    public void complete() {
        transitionTo(BookingStatus.COMPLETED);
    }

    /** Recorded after the refund is settled by the gateway, so the booking carries its own audit. */
    public void recordRefund(Money amount) {
        if (status != BookingStatus.CANCELLED) {
            throw new ValidationException("refunds are only recorded against cancelled bookings");
        }
        this.refundedAmount = amount;
    }

    private void transitionTo(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Booking", status, target);
        }
        this.status = target;
    }

    public boolean holdsInventory() {
        return status.holdsInventory();
    }

    public boolean isHoldExpired(Instant now) {
        return status == BookingStatus.PENDING_PAYMENT && now.isAfter(holdExpiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID propertyId() {
        return propertyId;
    }

    public UUID roomTypeId() {
        return roomTypeId;
    }

    public String guestName() {
        return guestName;
    }

    public String guestEmail() {
        return guestEmail;
    }

    public DateRange stay() {
        return stay;
    }

    public int guestCount() {
        return guestCount;
    }

    public int roomCount() {
        return roomCount;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public BookingStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }
}
