package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.domain.vo.Amenity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;

public record PropertyRequest(
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String locality,
        @NotBlank String addressLine,
        @Min(1) @Max(5) int starRating,
        Set<Amenity> amenities,
        @NotBlank String cancellationPolicyCode,
        @NotEmpty @Valid List<RoomTypeRequest> roomTypes) {
}
