package com.rupeek.hotelbooking.support;

import com.rupeek.hotelbooking.application.PropertyOnboardingService;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.PropertySpec;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.RoomTypeSpec;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds test properties through the real onboarding service.
 *
 * <p>Seeding via the service rather than by inserting rows means fixtures cannot describe a state
 * the application could never produce - notably a room type with no nightly inventory, which would
 * make availability tests pass for the wrong reason.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** A property whose single room type has exactly {@code rooms} rooms - the contention fixture. */
    public static Onboarded propertyWithRooms(PropertyOnboardingService onboarding,
                                              String uniqueName, int rooms, int maxOccupancy,
                                              String pricePerNight, String cancellationPolicyCode) {
        PropertyGroup group = onboarding.onboard(new OnboardPropertyGroupCommand(
                uniqueName + " Group",
                "owner+" + UUID.randomUUID() + "@example.com",
                List.of(new PropertySpec(
                        uniqueName,
                        "Bengaluru", "Indiranagar", "100 Ft Road",
                        4,
                        Set.of(Amenity.WIFI, Amenity.POOL),
                        cancellationPolicyCode,
                        List.of(new RoomTypeSpec("Deluxe King", maxOccupancy, rooms,
                                new BigDecimal(pricePerNight)))))));

        Property property = group.properties().get(0);
        RoomType roomType = property.roomTypes().get(0);
        return new Onboarded(group.id(), property.id(), roomType.id());
    }

    public static Onboarded flexibleProperty(PropertyOnboardingService onboarding,
                                             String uniqueName, int rooms) {
        return propertyWithRooms(onboarding, uniqueName, rooms, 2, "5000.00",
                FlexibleCancellationPolicy.CODE);
    }

    public record Onboarded(UUID groupId, UUID propertyId, UUID roomTypeId) {
    }
}
