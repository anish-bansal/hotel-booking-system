package com.rupeek.hotelbooking.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These tests pin down the half-open {@code [checkIn, checkOut)} convention, because every
 * availability calculation in the system depends on it. The check-out-day test is the important one:
 * if check-out ever started counting as a night, every property would appear one night more booked
 * than it is.
 */
class DateRangeTest {

    private static final LocalDate MAR_10 = LocalDate.of(2026, 3, 10);
    private static final LocalDate MAR_13 = LocalDate.of(2026, 3, 13);

    @Test
    @DisplayName("a 10th-to-13th stay occupies three nights: 10th, 11th, 12th")
    void checkOutDayIsNotANight() {
        DateRange stay = DateRange.of(MAR_10, MAR_13);

        assertThat(stay.nightCount()).isEqualTo(3);
        assertThat(stay.nights()).containsExactly(
                LocalDate.of(2026, 3, 10),
                LocalDate.of(2026, 3, 11),
                LocalDate.of(2026, 3, 12));
        assertThat(stay.contains(MAR_13)).isFalse();
    }

    @Test
    @DisplayName("one guest checking out as another checks in on the same day is not a conflict")
    void backToBackStaysDoNotOverlap() {
        DateRange leaving = DateRange.of(MAR_10, MAR_13);
        DateRange arriving = DateRange.of(MAR_13, LocalDate.of(2026, 3, 15));

        assertThat(leaving.overlaps(arriving)).isFalse();
        assertThat(arriving.overlaps(leaving)).isFalse();
    }

    @Test
    void detectsGenuineOverlap() {
        DateRange first = DateRange.of(MAR_10, MAR_13);
        DateRange straddling = DateRange.of(LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 14));
        DateRange enclosed = DateRange.of(LocalDate.of(2026, 3, 11), LocalDate.of(2026, 3, 12));

        assertThat(first.overlaps(straddling)).isTrue();
        assertThat(first.overlaps(enclosed)).isTrue();
        assertThat(enclosed.overlaps(first)).isTrue();
    }

    @Test
    void rejectsZeroNightAndInvertedStays() {
        assertThatThrownBy(() -> DateRange.of(MAR_10, MAR_10))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be strictly after");
        assertThatThrownBy(() -> DateRange.of(MAR_13, MAR_10))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsMissingDates() {
        assertThatThrownBy(() -> DateRange.of(null, MAR_13)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> DateRange.of(MAR_10, null)).isInstanceOf(ValidationException.class);
    }
}
