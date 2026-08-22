package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.application.result.PropertySearchResult;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record SearchResponse(int resultCount, List<Result> results) {

    public record Result(
            UUID propertyId,
            String propertyName,
            String city,
            String locality,
            int starRating,
            Set<Amenity> amenities,
            String cancellationPolicy,
            List<RoomOption> availableRoomTypes) {
    }

    public record RoomOption(
            UUID roomTypeId,
            String name,
            int maxOccupancy,
            int roomsRequiredForParty,
            int roomsAvailable,
            MoneyDto nightlyRate,
            MoneyDto totalForStay) {
    }

    public static SearchResponse from(List<PropertySearchResult> results) {
        List<Result> mapped = results.stream()
                .map(r -> new Result(
                        r.propertyId(), r.propertyName(), r.city(), r.locality(), r.starRating(),
                        r.amenities(), r.cancellationPolicy(),
                        r.availableRoomTypes().stream()
                                .map(o -> new RoomOption(
                                        o.roomTypeId(), o.name(), o.maxOccupancy(),
                                        o.roomsRequiredForParty(), o.roomsAvailable(),
                                        MoneyDto.from(o.nightlyRate()),
                                        MoneyDto.from(o.totalForStay())))
                                .toList()))
                .toList();
        return new SearchResponse(mapped.size(), mapped);
    }
}
