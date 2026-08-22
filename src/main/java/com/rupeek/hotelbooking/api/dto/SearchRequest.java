package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.domain.vo.Amenity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * A search, as a POST body.
 *
 * <p>POST rather than GET with query parameters, because the criteria set is open-ended by design:
 * amenity lists, price bands, and whatever filter gets added next. Encoding a growing, structured
 * object into a query string is how you end up with {@code ?amenities=WIFI&amenities=POOL&...} and a
 * URL length limit. The trade-off is that searches are not cacheable by URL, which is the right
 * price to pay for a request whose results depend on live inventory anyway.
 */
public record SearchRequest(
        @NotBlank String city,
        String locality,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @Min(1) int guests,
        BigDecimal minNightlyPrice,
        BigDecimal maxNightlyPrice,
        Set<Amenity> amenities,
        @Min(1) @Max(5) Integer minStarRating) {
}
