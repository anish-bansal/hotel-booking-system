package com.rupeek.hotelbooking.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Refund rules, tested exactly on their boundaries.
 *
 * <p>This is what injecting the clock buys. Check-in is fixed at midnight on 20 March and "now" is
 * moved to either side of each threshold by the second, so the tests pin down behaviour at
 * 24 hours minus a minute and 24 hours plus a minute. With {@code Instant.now()} buried inside the
 * policies, none of this would be expressible.
 */
class CancellationPolicyTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 3, 20);
    private static final Instant CHECK_IN_INSTANT = Instant.parse("2026-03-20T00:00:00Z");
    private static final Money PAID = Money.inr("10000.00");

    @Nested
    class Flexible {

        private final CancellationPolicy policy = new FlexibleCancellationPolicy();

        @Test
        @DisplayName("exactly 24 hours' notice still earns a full refund")
        void fullRefundAtTheBoundary() {
            RefundDecision decision = policy.evaluate(booking(), noticeOf(Duration.ofHours(24)));

            assertThat(decision.refundAmount()).isEqualTo(PAID);
            assertThat(decision.isRefundDue()).isTrue();
        }

        @Test
        @DisplayName("one minute inside 24 hours earns nothing")
        void nothingJustInsideTheWindow() {
            RefundDecision decision = policy.evaluate(booking(),
                    noticeOf(Duration.ofHours(24).minusMinutes(1)));

            assertThat(decision.refundAmount()).isEqualTo(Money.zero(Money.INR));
            assertThat(decision.isRefundDue()).isFalse();
        }

        @Test
        void generousNoticeEarnsAFullRefund() {
            assertThat(policy.evaluate(booking(), noticeOf(Duration.ofDays(30))).refundAmount())
                    .isEqualTo(PAID);
        }
    }

    @Nested
    class Moderate {

        private final CancellationPolicy policy = new ModerateCancellationPolicy();

        @Test
        void sevenDaysOrMoreEarnsEverything() {
            assertThat(policy.evaluate(booking(), noticeOf(Duration.ofDays(7))).refundAmount())
                    .isEqualTo(PAID);
        }

        @Test
        @DisplayName("a minute under seven days drops to the 50% tier, not to zero")
        void justUnderSevenDaysEarnsHalf() {
            RefundDecision decision = policy.evaluate(booking(),
                    noticeOf(Duration.ofDays(7).minusMinutes(1)));

            assertThat(decision.refundAmount()).isEqualTo(Money.inr("5000.00"));
            assertThat(decision.reason()).contains("50%");
        }

        @Test
        void insideTwentyFourHoursEarnsNothing() {
            assertThat(policy.evaluate(booking(), noticeOf(Duration.ofHours(1))).isRefundDue())
                    .isFalse();
        }

        @Test
        @DisplayName("cancelling after check-in has passed earns nothing")
        void afterCheckInEarnsNothing() {
            RefundDecision decision = policy.evaluate(booking(), CHECK_IN_INSTANT.plusSeconds(3600));

            assertThat(decision.isRefundDue()).isFalse();
        }
    }

    @Nested
    class NonRefundable {

        private final CancellationPolicy policy = new NonRefundableCancellationPolicy();

        @Test
        @DisplayName("no refund however much notice is given")
        void neverRefunds() {
            assertThat(policy.evaluate(booking(), noticeOf(Duration.ofDays(365))).isRefundDue())
                    .isFalse();
            assertThat(policy.evaluate(booking(), noticeOf(Duration.ofMinutes(1))).isRefundDue())
                    .isFalse();
        }

        @Test
        void refundIsZeroInTheBookingsOwnCurrency() {
            RefundDecision decision = policy.evaluate(booking(), noticeOf(Duration.ofDays(10)));

            assertThat(decision.refundAmount().currencyCode()).isEqualTo("INR");
            assertThat(decision.refundAmount().isZero()).isTrue();
        }
    }

    private static Instant noticeOf(Duration notice) {
        return CHECK_IN_INSTANT.minus(notice);
    }

    private static Booking booking() {
        return Booking.request(UUID.randomUUID(), UUID.randomUUID(), "Asha Menon",
                "asha@example.com", DateRange.of(CHECK_IN, CHECK_IN.plusDays(2)), 2, 1, PAID,
                Instant.parse("2026-03-01T10:00:00Z"), Duration.ofMinutes(15));
    }
}
