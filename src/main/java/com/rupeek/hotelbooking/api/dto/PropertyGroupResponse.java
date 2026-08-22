package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An owner account and everything under it.
 *
 * <p>{@code standalone} is computed from the property count on the way out, mirroring the domain:
 * the API reports the same derived answer the model gives rather than inventing a stored flag.
 */
public record PropertyGroupResponse(
        UUID id,
        String name,
        String contactEmail,
        boolean standalone,
        int propertyCount,
        List<PropertyResponse> properties) {

    public record PropertyResponse(
            UUID id,
            String name,
            String city,
            String locality,
            String addressLine,
            int starRating,
            Set<Amenity> amenities,
            String cancellationPolicyCode,
            List<RoomTypeResponse> roomTypes) {
    }

    public record RoomTypeResponse(
            UUID id,
            String name,
            int maxOccupancy,
            int totalRooms,
            MoneyDto basePricePerNight) {
    }

    public static PropertyGroupResponse from(PropertyGroup group) {
        return new PropertyGroupResponse(
                group.id(),
                group.name(),
                group.contactEmail(),
                group.isStandalone(),
                group.propertyCount(),
                group.properties().stream().map(PropertyGroupResponse::fromProperty).toList());
    }

    public static PropertyResponse fromProperty(Property property) {
        return new PropertyResponse(
                property.id(),
                property.name(),
                property.location().city(),
                property.location().locality(),
                property.location().addressLine(),
                property.starRating(),
                property.amenities(),
                property.cancellationPolicyCode(),
                property.roomTypes().stream().map(PropertyGroupResponse::fromRoomType).toList());
    }

    private static RoomTypeResponse fromRoomType(RoomType roomType) {
        return new RoomTypeResponse(
                roomType.id(),
                roomType.name(),
                roomType.maxOccupancy(),
                roomType.totalRooms(),
                MoneyDto.from(roomType.basePricePerNight()));
    }
}
