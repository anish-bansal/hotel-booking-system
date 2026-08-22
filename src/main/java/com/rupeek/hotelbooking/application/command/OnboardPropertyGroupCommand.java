package com.rupeek.hotelbooking.application.command;

import com.rupeek.hotelbooking.domain.vo.Amenity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Onboard an owner account together with its properties in one call.
 *
 * <p>There is no separate "onboard a single hotel" command. A solo hotel is this command with a
 * one-element list, which is what makes the single-property case a special case of the general one
 * rather than a parallel code path.
 */
public record OnboardPropertyGroupCommand(
        String groupName,
        String contactEmail,
        List<PropertySpec> properties) {

    public record PropertySpec(
            String name,
            String city,
            String locality,
            String addressLine,
            int starRating,
            Set<Amenity> amenities,
            String cancellationPolicyCode,
            List<RoomTypeSpec> roomTypes) {
    }

    public record RoomTypeSpec(
            String name,
            int maxOccupancy,
            int totalRooms,
            BigDecimal basePricePerNight) {
    }
}
