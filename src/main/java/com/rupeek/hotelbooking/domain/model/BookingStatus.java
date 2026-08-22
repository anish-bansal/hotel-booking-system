package com.rupeek.hotelbooking.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The booking lifecycle, with its legal transitions declared in one table.
 *
 * <pre>
 *   PENDING_PAYMENT ──pay ok──▶ CONFIRMED ──stay over──▶ COMPLETED
 *         │                         │
 *         ├──guest cancels──▶ CANCELLED ◀──guest cancels──┘
 *         └──hold lapses────▶ EXPIRED
 * </pre>
 *
 * <p>Keeping the transition table here rather than as {@code if} statements in a service means
 * there is exactly one place to read to know what the lifecycle is, and exactly one place to edit
 * to change it. {@link #holdsInventory()} is the second reason this enum earns its keep: cancellation
 * and the expiry sweeper both need to know whether a booking is still occupying rooms, and asking
 * the status is better than both of them re-deriving it from a list of state names.
 */
public enum BookingStatus {

    /** Created and holding inventory, waiting for a successful payment before it lapses. */
    PENDING_PAYMENT,

    /** Paid for. Holds inventory until the stay completes or the guest cancels. */
    CONFIRMED,

    /** Cancelled by the guest. Inventory released; refund settled per the property's policy. */
    CANCELLED,

    /** The payment hold lapsed before payment succeeded. Inventory released. */
    EXPIRED,

    /** The stay happened. Terminal and non-refundable. */
    COMPLETED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(BookingStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PENDING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED, EXPIRED));
        ALLOWED_TRANSITIONS.put(CONFIRMED, EnumSet.of(CANCELLED, COMPLETED));
        ALLOWED_TRANSITIONS.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        ALLOWED_TRANSITIONS.put(EXPIRED, EnumSet.noneOf(BookingStatus.class));
        ALLOWED_TRANSITIONS.put(COMPLETED, EnumSet.noneOf(BookingStatus.class));
    }

    public boolean canTransitionTo(BookingStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public Set<BookingStatus> allowedNextStates() {
        return EnumSet.copyOf(ALLOWED_TRANSITIONS.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** True while this booking is still occupying rooms that would otherwise be sellable. */
    public boolean holdsInventory() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }

    /** True when the guest is entitled to ask for their money back. */
    public boolean isRefundable() {
        return this == CONFIRMED;
    }
}
