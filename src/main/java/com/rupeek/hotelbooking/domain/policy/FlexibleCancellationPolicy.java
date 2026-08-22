package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Full refund up to 24 hours before check-in; nothing after that. */
public class FlexibleCancellationPolicy implements CancellationPolicy {

    public static final String CODE = "FLEXIBLE";

    private static final Duration FREE_CANCELLATION_WINDOW = Duration.ofHours(24);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Free cancellation until 24 hours before check-in";
    }

    @Override
    public RefundDecision evaluate(Booking booking, Instant now) {
        Instant checkIn = booking.stay().checkIn().atStartOfDay().toInstant(ZoneOffset.UTC);
        Duration noticeGiven = Duration.between(now, checkIn);

        if (noticeGiven.compareTo(FREE_CANCELLATION_WINDOW) >= 0) {
            return RefundDecision.full(booking.totalAmount(),
                    "Cancelled more than 24 hours before check-in: full refund");
        }
        return RefundDecision.none(Money.zero(booking.totalAmount().currency()),
                "Cancelled within 24 hours of check-in: no refund");
    }
}
