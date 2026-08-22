package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Instant;

/**
 * No money back, ever — the discounted-rate policy.
 *
 * <p>Worth noting the booking is still cancelled and the rooms still go back on sale. Refund policy
 * and inventory release are separate concerns, and conflating them would mean a non-refundable
 * booking sterilised its rooms for a stay nobody was going to turn up for.
 */
public class NonRefundableCancellationPolicy implements CancellationPolicy {

    public static final String CODE = "NON_REFUNDABLE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Non-refundable rate: cancellation releases the room but returns no money";
    }

    @Override
    public RefundDecision evaluate(Booking booking, Instant now) {
        return RefundDecision.none(Money.zero(booking.totalAmount().currency()),
                "Non-refundable rate: no refund on cancellation");
    }
}
