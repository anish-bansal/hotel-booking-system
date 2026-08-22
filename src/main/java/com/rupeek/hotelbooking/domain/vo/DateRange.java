package com.rupeek.hotelbooking.domain.vo;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A stay expressed as {@code [checkIn, checkOut)} — check-in inclusive, check-out exclusive.
 *
 * <p>That half-open convention is the single most important decision in this class. It means the
 * set of <em>nights</em> a stay occupies is exactly {@code checkIn..checkOut-1}, so two stays
 * collide if and only if their night sets intersect. A guest checking out on the 5th and another
 * checking in on the 5th are not in conflict, which falls out of the model for free instead of
 * needing an off-by-one special case at every call site.
 */
@Embeddable
public final class DateRange {

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    protected DateRange() {
        // for JPA
    }

    private DateRange(LocalDate checkIn, LocalDate checkOut) {
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public static DateRange of(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new ValidationException("checkIn and checkOut are both required");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new ValidationException(
                    "checkOut (" + checkOut + ") must be strictly after checkIn (" + checkIn + ")");
        }
        return new DateRange(checkIn, checkOut);
    }

    /** The nights this stay occupies. This is the unit inventory is held against. */
    public List<LocalDate> nights() {
        return checkIn.datesUntil(checkOut).toList();
    }

    public Stream<LocalDate> nightStream() {
        return checkIn.datesUntil(checkOut);
    }

    public long nightCount() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public boolean overlaps(DateRange other) {
        return checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(checkOut);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(checkIn) && date.isBefore(checkOut);
    }

    public LocalDate checkIn() {
        return checkIn;
    }

    public LocalDate checkOut() {
        return checkOut;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DateRange other)) {
            return false;
        }
        return checkIn.equals(other.checkIn) && checkOut.equals(other.checkOut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(checkIn, checkOut);
    }

    @Override
    public String toString() {
        return "[" + checkIn + " -> " + checkOut + ")";
    }
}
