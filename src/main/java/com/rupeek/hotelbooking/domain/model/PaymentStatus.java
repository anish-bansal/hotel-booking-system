package com.rupeek.hotelbooking.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle, with the same declarative-transition approach as {@link BookingStatus}.
 *
 * <pre>
 *   INITIATED ──gateway ok──▶ SUCCESSFUL ──cancellation──▶ REFUNDED
 *       └─────gateway declines──▶ FAILED
 * </pre>
 */
public enum PaymentStatus {

    INITIATED,
    SUCCESSFUL,
    FAILED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(INITIATED, EnumSet.of(SUCCESSFUL, FAILED));
        ALLOWED_TRANSITIONS.put(SUCCESSFUL, EnumSet.of(REFUNDED));
        ALLOWED_TRANSITIONS.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
