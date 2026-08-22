package com.rupeek.hotelbooking.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The point of these tests is that the invariants are enforced by the entity, not by the services
 * around it. Every illegal move is attempted directly on a bare {@code Booking} with no Spring
 * context, no database and no service in the way — if it is rejected here, it cannot be smuggled
 * past by any caller.
 */
class BookingTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    @Test
    void startsUnpaidAndHoldingInventory() {
        Booking booking = newBooking();

        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.holdsInventory()).isTrue();
        assertThat(booking.holdExpiresAt()).isEqualTo(NOW.plus(FIFTEEN_MINUTES));
    }

    @Test
    void confirmsOnPayment() {
        Booking booking = newBooking();
        Instant paidAt = NOW.plusSeconds(120);

        booking.confirm(paidAt);

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.confirmedAt()).isEqualTo(paidAt);
    }

    @Test
    @DisplayName("cancelling twice is rejected - the second attempt cannot double-refund")
    void rejectsDoubleCancellation() {
        Booking booking = newBooking();
        booking.confirm(NOW);
        booking.cancel(NOW.plusSeconds(60));

        assertThatThrownBy(() -> booking.cancel(NOW.plusSeconds(120)))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("CANCELLED");
        assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    @DisplayName("a cancelled booking cannot be confirmed by a late payment")
    void rejectsConfirmingACancelledBooking() {
        Booking booking = newBooking();
        booking.cancel(NOW);

        assertThatThrownBy(() -> booking.confirm(NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void rejectsExpiringAConfirmedBooking() {
        Booking booking = newBooking();
        booking.confirm(NOW);

        assertThatThrownBy(booking::expire).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void holdLapsesOnlyWhileUnpaidAndOnlyAfterTheDeadline() {
        Booking booking = newBooking();

        assertThat(booking.isHoldExpired(NOW.plus(Duration.ofMinutes(14)))).isFalse();
        assertThat(booking.isHoldExpired(NOW.plus(Duration.ofMinutes(16)))).isTrue();

        booking.confirm(NOW);
        assertThat(booking.isHoldExpired(NOW.plus(Duration.ofDays(1))))
                .as("a paid booking's hold is irrelevant - it is not waiting for anything")
                .isFalse();
    }

    @Test
    @DisplayName("a refund can only be recorded against a cancelled booking")
    void refundRequiresCancellation() {
        Booking booking = newBooking();
        booking.confirm(NOW);

        assertThatThrownBy(() -> booking.recordRefund(Money.inr(100)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cancelled");

        booking.cancel(NOW.plusSeconds(10));
        booking.recordRefund(Money.inr(100));
        assertThat(booking.refundedAmount()).isEqualTo(Money.inr(100));
    }

    @Test
    void rejectsInvalidConstructionArguments() {
        assertThatThrownBy(() -> Booking.request(UUID.randomUUID(), UUID.randomUUID(), "  ",
                "guest@example.com", threeNights(), 2, 1, Money.inr(1000), NOW, FIFTEEN_MINUTES))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("guestName");

        assertThatThrownBy(() -> Booking.request(UUID.randomUUID(), UUID.randomUUID(), "Guest",
                "not-an-email", threeNights(), 2, 1, Money.inr(1000), NOW, FIFTEEN_MINUTES))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("guestEmail");

        assertThatThrownBy(() -> Booking.request(UUID.randomUUID(), UUID.randomUUID(), "Guest",
                "guest@example.com", threeNights(), 0, 1, Money.inr(1000), NOW, FIFTEEN_MINUTES))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("guestCount");
    }

    private static Booking newBooking() {
        return Booking.request(UUID.randomUUID(), UUID.randomUUID(), "Asha Menon",
                "asha@example.com", threeNights(), 2, 1, Money.inr("19500.00"), NOW, FIFTEEN_MINUTES);
    }

    private static DateRange threeNights() {
        return DateRange.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 13));
    }
}
