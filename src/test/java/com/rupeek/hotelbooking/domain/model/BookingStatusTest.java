package com.rupeek.hotelbooking.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle contract, asserted directly against the transition table. */
class BookingStatusTest {

    @Test
    void unpaidBookingCanBeConfirmedCancelledOrExpired() {
        assertThat(BookingStatus.PENDING_PAYMENT.allowedNextStates())
                .containsExactlyInAnyOrder(BookingStatus.CONFIRMED, BookingStatus.CANCELLED,
                        BookingStatus.EXPIRED);
    }

    @Test
    void confirmedBookingCanOnlyBeCancelledOrCompleted() {
        assertThat(BookingStatus.CONFIRMED.allowedNextStates())
                .containsExactlyInAnyOrder(BookingStatus.CANCELLED, BookingStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class,
            names = {"CANCELLED", "EXPIRED", "COMPLETED"})
    @DisplayName("terminal states admit no further transitions")
    void terminalStatesAreFinal(BookingStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (BookingStatus target : BookingStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("an expired booking cannot be revived by a late payment")
    void expiredCannotBecomeConfirmed() {
        assertThat(BookingStatus.EXPIRED.canTransitionTo(BookingStatus.CONFIRMED)).isFalse();
    }

    @Test
    @DisplayName("only unpaid and confirmed bookings occupy rooms")
    void inventoryHoldingStates() {
        assertThat(BookingStatus.PENDING_PAYMENT.holdsInventory()).isTrue();
        assertThat(BookingStatus.CONFIRMED.holdsInventory()).isTrue();
        assertThat(BookingStatus.CANCELLED.holdsInventory()).isFalse();
        assertThat(BookingStatus.EXPIRED.holdsInventory()).isFalse();
        assertThat(BookingStatus.COMPLETED.holdsInventory()).isFalse();
    }

    @Test
    void everyStatusHasATransitionRule() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.allowedNextStates()).isNotNull();
        }
    }
}
