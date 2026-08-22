package com.rupeek.hotelbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.application.command.AddPropertyCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.PropertySpec;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.RoomTypeSpec;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.ModerateCancellationPolicy;
import com.rupeek.hotelbooking.domain.port.RoomInventoryRepository;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.support.TestClockConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Onboarding, including the claim that a standalone property is not a special path.
 *
 * <p>The test that matters most here is {@link #standaloneOwnerGrowsIntoAChain}: it takes an owner
 * onboarded as a single hotel and gives them a second property through the ordinary endpoint. If
 * "single" were modelled as its own thing, that operation would need a conversion step. It does not,
 * and the test asserts that it does not.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class PropertyOnboardingIntegrationTest {

    @Autowired
    private PropertyOnboardingService onboardingService;

    @Autowired
    private RoomInventoryRepository inventoryRepository;

    @Value("${hotel-booking.inventory.booking-horizon-days}")
    private int horizonDays;

    @Test
    @DisplayName("a solo hotel and a chain go through the identical code path")
    void oneCommandOnboardsBothShapes() {
        PropertyGroup solo = onboardingService.onboard(
                new OnboardPropertyGroupCommand(unique("Solo"), email(), List.of(spec("Solo Hotel"))));

        PropertyGroup chain = onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Chain"), email(),
                List.of(spec("Chain North"), spec("Chain South"), spec("Chain East"))));

        assertThat(solo.isStandalone()).isTrue();
        assertThat(solo.propertyCount()).isEqualTo(1);
        assertThat(chain.isStandalone()).isFalse();
        assertThat(chain.propertyCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a standalone owner becomes a chain by adding a property, with no conversion step")
    void standaloneOwnerGrowsIntoAChain() {
        PropertyGroup solo = onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Growing"), email(), List.of(spec("First Hotel"))));
        assertThat(solo.isStandalone()).isTrue();

        Property second = onboardingService.addProperty(
                new AddPropertyCommand(solo.id(), spec("Second Hotel")));

        PropertyGroup reloaded = onboardingService.requireGroup(solo.id());
        assertThat(reloaded.isStandalone()).isFalse();
        assertThat(reloaded.propertyCount()).isEqualTo(2);
        assertThat(reloaded.properties()).extracting(Property::name)
                .contains("First Hotel", "Second Hotel");
        assertThat(second.group().id()).isEqualTo(solo.id());
    }

    @Test
    @DisplayName("onboarding opens nightly inventory, so a new property is bookable at once")
    void onboardingOpensInventory() {
        PropertyGroup group = onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Inventory"), email(), List.of(spec("Inventory Hotel"))));

        UUID roomTypeId = group.properties().get(0).roomTypes().get(0).id();

        assertThat(inventoryRepository.countByRoomTypeId(roomTypeId))
                .as("one inventory row per night of the booking horizon")
                .isEqualTo(horizonDays);
    }

    @Test
    @DisplayName("different properties in one chain can run different cancellation policies")
    void policiesArePerPropertyNotPerOwner() {
        PropertyGroup group = onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Mixed"), email(),
                List.of(
                        specWithPolicy("Flexible Wing", FlexibleCancellationPolicy.CODE),
                        specWithPolicy("Moderate Wing", ModerateCancellationPolicy.CODE))));

        assertThat(group.properties()).extracting(Property::cancellationPolicyCode)
                .containsExactlyInAnyOrder("FLEXIBLE", "MODERATE");
    }

    @Test
    @DisplayName("an unknown policy code is rejected at onboarding, not at the guest's cancellation")
    void unknownPolicyCodeIsRejectedEarly() {
        assertThatThrownBy(() -> onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Bad Policy"), email(),
                List.of(specWithPolicy("Bad Hotel", "SUPER_GENEROUS")))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("SUPER_GENEROUS");
    }

    @Test
    void rejectsAnOwnerWithNoProperties() {
        assertThatThrownBy(() -> onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Empty"), email(), List.of())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one property");
    }

    @Test
    @DisplayName("a property with no room types is refused - it could never be booked")
    void rejectsAPropertyWithNoRoomTypes() {
        PropertySpec roomless = new PropertySpec("Roomless Hotel", "Bengaluru", "Koramangala",
                "5th Block", 3, Set.of(Amenity.WIFI), FlexibleCancellationPolicy.CODE, List.of());

        assertThatThrownBy(() -> onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Roomless"), email(), List.of(roomless))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one room type");
    }

    @Test
    void rejectsAnOutOfRangeStarRating() {
        PropertySpec sixStar = new PropertySpec("Six Star Hotel", "Bengaluru", "Koramangala",
                "5th Block", 6, Set.of(Amenity.WIFI), FlexibleCancellationPolicy.CODE,
                List.of(new RoomTypeSpec("Standard", 2, 3, new BigDecimal("4000.00"))));

        assertThatThrownBy(() -> onboardingService.onboard(new OnboardPropertyGroupCommand(
                unique("Six Star"), email(), List.of(sixStar))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("starRating");
    }

    private static PropertySpec spec(String name) {
        return specWithPolicy(name, FlexibleCancellationPolicy.CODE);
    }

    private static PropertySpec specWithPolicy(String name, String policyCode) {
        return new PropertySpec(name, "Bengaluru", "Koramangala", "5th Block", 4,
                Set.of(Amenity.WIFI, Amenity.PARKING), policyCode,
                List.of(new RoomTypeSpec("Standard Double", 2, 4, new BigDecimal("4000.00"))));
    }

    private static String unique(String prefix) {
        return prefix + " Group " + System.nanoTime();
    }

    private static String email() {
        return "owner+" + UUID.randomUUID() + "@example.com";
    }
}
