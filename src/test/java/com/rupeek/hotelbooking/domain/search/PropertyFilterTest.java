package com.rupeek.hotelbooking.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Location;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Each filter tested in isolation, with no database and no Spring context.
 *
 * <p>That these tests need nothing but {@code new} is the evidence that the filter abstraction is at
 * the right seam. A filter is a pure predicate over a property and the criteria; if testing one
 * required booting a context, the seam would be in the wrong place.
 */
class PropertyFilterTest {

    private static final DateRange STAY =
            DateRange.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 13));

    @Nested
    class PriceRange {

        private final PropertyFilter filter = new PriceRangeFilter();

        @Test
        @DisplayName("not applicable when the guest gave no budget, so it never eliminates anything")
        void inapplicableWithoutABudget() {
            assertThat(filter.isApplicable(criteria().build())).isFalse();
        }

        @Test
        @DisplayName("judged on the cheapest room, so an expensive suite does not hide a cheap room")
        void judgedOnTheCheapestRoomType() {
            Property property = propertyWithRooms(
                    RoomType.create("Standard", 2, 4, Money.inr("4000.00")),
                    RoomType.create("Presidential Suite", 4, 1, Money.inr("40000.00")));

            PropertySearchCriteria budget = criteria()
                    .priceBetween(null, Money.inr("5000.00")).build();

            assertThat(filter.isApplicable(budget)).isTrue();
            assertThat(filter.matches(property, budget)).isTrue();
        }

        @Test
        void excludesPropertiesAboveTheCeiling() {
            Property property = propertyWithRooms(
                    RoomType.create("Standard", 2, 4, Money.inr("9000.00")));

            assertThat(filter.matches(property,
                    criteria().priceBetween(null, Money.inr("5000.00")).build())).isFalse();
        }

        @Test
        void excludesPropertiesBelowTheFloor() {
            Property property = propertyWithRooms(
                    RoomType.create("Standard", 2, 4, Money.inr("1500.00")));

            assertThat(filter.matches(property,
                    criteria().priceBetween(Money.inr("3000.00"), null).build())).isFalse();
        }

        @Test
        @DisplayName("the boundary is inclusive at both ends")
        void boundariesAreInclusive() {
            Property property = propertyWithRooms(
                    RoomType.create("Standard", 2, 4, Money.inr("5000.00")));

            assertThat(filter.matches(property,
                    criteria().priceBetween(Money.inr("5000.00"), Money.inr("5000.00")).build()))
                    .isTrue();
        }
    }

    @Nested
    class Amenities {

        private final PropertyFilter filter = new AmenityFilter();

        @Test
        void inapplicableWhenNoAmenitiesRequested() {
            assertThat(filter.isApplicable(criteria().build())).isFalse();
        }

        @Test
        @DisplayName("requires every requested amenity, not any of them")
        void requiresAllRequestedAmenities() {
            Property poolOnly = propertyWith(Set.of(Amenity.WIFI, Amenity.POOL));

            PropertySearchCriteria wantsPoolAndPets = criteria()
                    .withAmenities(Set.of(Amenity.POOL, Amenity.PET_FRIENDLY)).build();
            PropertySearchCriteria wantsPool = criteria()
                    .withAmenities(Set.of(Amenity.POOL)).build();

            assertThat(filter.matches(poolOnly, wantsPoolAndPets)).isFalse();
            assertThat(filter.matches(poolOnly, wantsPool)).isTrue();
        }
    }

    @Nested
    class StarRating {

        private final PropertyFilter filter = new StarRatingFilter();

        @Test
        void inapplicableWhenNoRatingRequested() {
            assertThat(filter.isApplicable(criteria().build())).isFalse();
        }

        @Test
        @DisplayName("minimum rating is a floor, not an exact match")
        void keepsPropertiesAtOrAboveTheFloor() {
            Property fourStar = propertyWith(Set.of(Amenity.WIFI));

            assertThat(filter.matches(fourStar, criteria().minStarRating(4).build())).isTrue();
            assertThat(filter.matches(fourStar, criteria().minStarRating(3).build())).isTrue();
            assertThat(filter.matches(fourStar, criteria().minStarRating(5).build())).isFalse();
        }
    }

    private static PropertySearchCriteria.Builder criteria() {
        return PropertySearchCriteria.in("Bengaluru").stay(STAY).guests(2);
    }

    private static Property propertyWith(Set<Amenity> amenities) {
        Property property = Property.create("Test Hotel",
                Location.of("Bengaluru", "Indiranagar", "100 Ft Road"),
                4, amenities, FlexibleCancellationPolicy.CODE);
        property.addRoomType(RoomType.create("Standard", 2, 4, Money.inr("5000.00")));
        return property;
    }

    private static Property propertyWithRooms(RoomType... roomTypes) {
        Property property = Property.create("Test Hotel",
                Location.of("Bengaluru", "Indiranagar", "100 Ft Road"),
                4, Set.of(Amenity.WIFI), FlexibleCancellationPolicy.CODE);
        for (RoomType roomType : roomTypes) {
            property.addRoomType(roomType);
        }
        return property;
    }
}
