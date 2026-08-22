package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.util.EnumSet;
import java.util.Set;

/**
 * Everything a guest asked for, in one immutable object.
 *
 * <p>City, dates and party size are mandatory — without them "available" is not a question that can
 * be answered. Everything else is an optional narrowing, and a {@code null} means "the guest did not
 * express a preference" rather than "no". Each {@link PropertyFilter} decides for itself whether the
 * criteria it cares about were supplied, which is how a new optional criterion gets added without
 * every other filter learning about it.
 */
public record PropertySearchCriteria(
        String city,
        String locality,
        DateRange stay,
        int guests,
        Money minNightlyPrice,
        Money maxNightlyPrice,
        Set<Amenity> requiredAmenities,
        Integer minStarRating) {

    public PropertySearchCriteria {
        if (city == null || city.isBlank()) {
            throw new ValidationException("city is required to search");
        }
        if (stay == null) {
            throw new ValidationException("checkIn and checkOut are required to search");
        }
        if (guests < 1) {
            throw new ValidationException("guests must be at least 1 but was " + guests);
        }
        if (minNightlyPrice != null && maxNightlyPrice != null
                && minNightlyPrice.isGreaterThan(maxNightlyPrice)) {
            throw new ValidationException("minNightlyPrice cannot exceed maxNightlyPrice");
        }
        if (minStarRating != null && (minStarRating < 1 || minStarRating > 5)) {
            throw new ValidationException("minStarRating must be within [1, 5] but was " + minStarRating);
        }
        requiredAmenities = requiredAmenities == null || requiredAmenities.isEmpty()
                ? EnumSet.noneOf(Amenity.class)
                : EnumSet.copyOf(requiredAmenities);
    }

    public static Builder in(String city) {
        return new Builder(city);
    }

    /** Builder purely for legibility at call sites — six optional fields as positional args is unreadable. */
    public static final class Builder {

        private final String city;
        private String locality;
        private DateRange stay;
        private int guests = 1;
        private Money minNightlyPrice;
        private Money maxNightlyPrice;
        private Set<Amenity> requiredAmenities = EnumSet.noneOf(Amenity.class);
        private Integer minStarRating;

        private Builder(String city) {
            this.city = city;
        }

        public Builder locality(String locality) {
            this.locality = locality;
            return this;
        }

        public Builder stay(DateRange stay) {
            this.stay = stay;
            return this;
        }

        public Builder guests(int guests) {
            this.guests = guests;
            return this;
        }

        public Builder priceBetween(Money min, Money max) {
            this.minNightlyPrice = min;
            this.maxNightlyPrice = max;
            return this;
        }

        public Builder withAmenities(Set<Amenity> amenities) {
            this.requiredAmenities = amenities;
            return this;
        }

        public Builder minStarRating(Integer minStarRating) {
            this.minStarRating = minStarRating;
            return this;
        }

        public PropertySearchCriteria build() {
            return new PropertySearchCriteria(city, locality, stay, guests, minNightlyPrice,
                    maxNightlyPrice, requiredAmenities, minStarRating);
        }
    }
}
