package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Tiered refund: everything with a week's notice, half with a day's, nothing inside that.
 *
 * <p>The tiers are declared as an ordered list so the rule reads top to bottom the way the terms
 * and conditions do, and adding a tier is one line rather than another nested {@code else if}.
 */
public class ModerateCancellationPolicy implements CancellationPolicy {

    public static final String CODE = "MODERATE";

    private record Tier(Duration minimumNotice, int refundPercent, String label) {
    }

    private static final Tier[] TIERS = {
            new Tier(Duration.ofDays(7), 100, "7 days or more"),
            new Tier(Duration.ofHours(24), 50, "between 24 hours and 7 days"),
    };

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "Full refund 7+ days before check-in, 50% within 7 days, none within 24 hours";
    }

    @Override
    public RefundDecision evaluate(Booking booking, Instant now) {
        Instant checkIn = booking.stay().checkIn().atStartOfDay().toInstant(ZoneOffset.UTC);
        Duration noticeGiven = Duration.between(now, checkIn);

        for (Tier tier : TIERS) {
            if (noticeGiven.compareTo(tier.minimumNotice()) >= 0) {
                return new RefundDecision(
                        booking.totalAmount().percentage(tier.refundPercent()),
                        "Cancelled with " + tier.label() + " notice: " + tier.refundPercent() + "% refund");
            }
        }
        return RefundDecision.none(Money.zero(booking.totalAmount().currency()),
                "Cancelled within 24 hours of check-in: no refund");
    }
}
