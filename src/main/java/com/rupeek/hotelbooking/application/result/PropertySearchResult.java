package com.rupeek.hotelbooking.application.result;

import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One property that is genuinely bookable for the requested dates, with its available options.
 *
 * <p>Each option carries the price for the <em>whole stay</em> as well as the nightly rate, so the
 * number a guest compares in search is the same number they are charged at checkout. Quoting a
 * nightly rate and then billing a different total is one of the easier ways to lose a customer's
 * trust, and it is avoided here by running search and booking through the same
 * {@code PricingStrategy}.
 */
public record PropertySearchResult(
        UUID propertyId,
        String propertyName,
        String city,
        String locality,
        int starRating,
        Set<Amenity> amenities,
        String cancellationPolicy,
        List<RoomTypeOption> availableRoomTypes) {

    public record RoomTypeOption(
            UUID roomTypeId,
            String name,
            int maxOccupancy,
            int roomsRequiredForParty,
            int roomsAvailable,
            Money nightlyRate,
            Money totalForStay) {
    }
}
