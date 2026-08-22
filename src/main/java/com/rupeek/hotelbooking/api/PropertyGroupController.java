package com.rupeek.hotelbooking.api;

import com.rupeek.hotelbooking.api.dto.OnboardGroupRequest;
import com.rupeek.hotelbooking.api.dto.PropertyGroupResponse;
import com.rupeek.hotelbooking.api.dto.PropertyRequest;
import com.rupeek.hotelbooking.application.PropertyOnboardingService;
import com.rupeek.hotelbooking.application.command.AddPropertyCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Property onboarding.
 *
 * <p>The resource is the owner account, not the property, because ownership is the thing that has an
 * identity on this platform — a property cannot exist without one. That makes "add a property to an
 * owner" a sub-resource POST, and it makes a solo hotel and a chain the same endpoint with a
 * different list length.
 *
 * <p>Controllers here do three things and nothing else: translate a DTO into a command, call one
 * service method, translate the result back. No validation logic, no orchestration, no branching —
 * anything that looks like a decision belongs a layer down where it can be tested without HTTP.
 */
@RestController
@RequestMapping("/api/v1/property-groups")
public class PropertyGroupController {

    private final PropertyOnboardingService onboardingService;

    public PropertyGroupController(PropertyOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public ResponseEntity<PropertyGroupResponse> onboard(@Valid @RequestBody OnboardGroupRequest request) {
        PropertyGroup group = onboardingService.onboard(new OnboardPropertyGroupCommand(
                request.groupName(),
                request.contactEmail(),
                request.properties().stream().map(PropertyGroupController::toSpec).toList()));

        return ResponseEntity.status(HttpStatus.CREATED).body(PropertyGroupResponse.from(group));
    }

    @GetMapping("/{groupId}")
    public PropertyGroupResponse get(@PathVariable UUID groupId) {
        return PropertyGroupResponse.from(onboardingService.requireGroup(groupId));
    }

    @PostMapping("/{groupId}/properties")
    public ResponseEntity<PropertyGroupResponse.PropertyResponse> addProperty(
            @PathVariable UUID groupId, @Valid @RequestBody PropertyRequest request) {

        Property property = onboardingService.addProperty(
                new AddPropertyCommand(groupId, toSpec(request)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PropertyGroupResponse.fromProperty(property));
    }

    /** Discoverability: which cancellation policies this deployment actually has registered. */
    @GetMapping("/cancellation-policies")
    public Map<String, List<String>> cancellationPolicies() {
        return Map.of("supported", onboardingService.supportedCancellationPolicies());
    }

    private static OnboardPropertyGroupCommand.PropertySpec toSpec(PropertyRequest request) {
        return new OnboardPropertyGroupCommand.PropertySpec(
                request.name(),
                request.city(),
                request.locality(),
                request.addressLine(),
                request.starRating(),
                request.amenities() == null ? Set.of() : request.amenities(),
                request.cancellationPolicyCode(),
                request.roomTypes().stream()
                        .map(rt -> new OnboardPropertyGroupCommand.RoomTypeSpec(
                                rt.name(), rt.maxOccupancy(), rt.totalRooms(), rt.basePricePerNight()))
                        .toList());
    }
}
