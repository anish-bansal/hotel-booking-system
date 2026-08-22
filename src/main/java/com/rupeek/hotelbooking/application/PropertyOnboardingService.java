package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.application.command.AddPropertyCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicyRegistry;
import com.rupeek.hotelbooking.domain.port.PropertyGroupRepository;
import com.rupeek.hotelbooking.domain.vo.Location;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings owners and their properties onto the platform.
 *
 * <p><b>One path, not two.</b> A standalone hotel and a fifty-property chain are onboarded by the
 * same method. {@link #onboard} builds a {@link PropertyGroup} and adds however many properties the
 * command carries — one or fifty — so there is no branch, no {@code isSingleProperty} flag, and no
 * second code path to keep in step with the first. {@link #addProperty} is how a group that already
 * exists grows, which is also how a solo owner becomes a chain: no migration, no re-onboarding,
 * because they were always the same shape.
 *
 * <p>Onboarding also opens nightly inventory for every room type created, so a property is bookable
 * the moment it exists. Doing that here keeps "a room type without inventory" out of the system
 * entirely rather than leaving it as a state later code has to defend against.
 */
@Service
public class PropertyOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(PropertyOnboardingService.class);

    private final PropertyGroupRepository propertyGroupRepository;
    private final InventoryService inventoryService;
    private final CancellationPolicyRegistry cancellationPolicies;
    private final Clock clock;

    public PropertyOnboardingService(PropertyGroupRepository propertyGroupRepository,
                                     InventoryService inventoryService,
                                     CancellationPolicyRegistry cancellationPolicies,
                                     Clock clock) {
        this.propertyGroupRepository = propertyGroupRepository;
        this.inventoryService = inventoryService;
        this.cancellationPolicies = cancellationPolicies;
        this.clock = clock;
    }

    @Transactional
    public PropertyGroup onboard(OnboardPropertyGroupCommand command) {
        if (command.properties() == null || command.properties().isEmpty()) {
            throw new ValidationException("at least one property is required to onboard an owner");
        }

        PropertyGroup group = PropertyGroup.named(command.groupName(), command.contactEmail());
        command.properties().forEach(spec -> group.addProperty(buildProperty(spec)));

        PropertyGroup saved = propertyGroupRepository.save(group);
        saved.properties().forEach(this::openInventoryFor);

        log.info("Onboarded {} '{}' with {} property/properties",
                saved.isStandalone() ? "standalone owner" : "multi-property owner",
                saved.name(), saved.propertyCount());
        return saved;
    }

    /**
     * Grows an existing group by one property — which is also how a solo owner becomes a chain.
     *
     * <p><b>Why the return value of {@code save} is what gets used.</b> The group is already managed,
     * so the save routes to a JPA merge; the property just added to it is still transient, and
     * cascade-merge persists a <em>copy</em> of it. Identifiers are assigned to that copy, not to the
     * instance built here — so opening inventory against the local instance would read a null room
     * type id. Working from the persisted graph is the only way to see the ids that were actually
     * generated.
     *
     * <p>The new property is located by set difference on ids rather than by name, so a group holding
     * two properties with the same name still resolves unambiguously.
     */
    @Transactional
    public Property addProperty(AddPropertyCommand command) {
        PropertyGroup group = propertyGroupRepository.findById(command.groupId())
                .orElseThrow(() -> new NotFoundException("PropertyGroup", command.groupId()));

        Set<UUID> existingIds = group.properties().stream()
                .map(Property::id)
                .collect(Collectors.toSet());

        group.addProperty(buildProperty(command.property()));
        PropertyGroup saved = propertyGroupRepository.save(group);

        Property property = saved.properties().stream()
                .filter(candidate -> !existingIds.contains(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Property was not persisted into group " + command.groupId()));

        openInventoryFor(property);

        log.info("Added property '{}' to group '{}' (now {} properties)",
                property.name(), saved.name(), saved.propertyCount());
        return property;
    }

    @Transactional(readOnly = true)
    public PropertyGroup requireGroup(UUID groupId) {
        return propertyGroupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("PropertyGroup", groupId));
    }

    public List<String> supportedCancellationPolicies() {
        return List.copyOf(cancellationPolicies.registeredCodes());
    }

    private Property buildProperty(OnboardPropertyGroupCommand.PropertySpec spec) {
        if (spec.roomTypes() == null || spec.roomTypes().isEmpty()) {
            throw new ValidationException("property '" + spec.name()
                    + "' needs at least one room type to be bookable");
        }
        // Validate the policy code at onboarding time. Accepting an unknown code here would defer
        // the failure to the guest's cancellation attempt, which is the worst possible moment.
        cancellationPolicies.resolve(spec.cancellationPolicyCode());

        Property property = Property.create(
                spec.name(),
                Location.of(spec.city(), spec.locality(), spec.addressLine()),
                spec.starRating(),
                spec.amenities(),
                spec.cancellationPolicyCode());

        spec.roomTypes().forEach(rt -> property.addRoomType(RoomType.create(
                rt.name(), rt.maxOccupancy(), rt.totalRooms(),
                Money.of(rt.basePricePerNight(), Money.INR))));

        return property;
    }

    private void openInventoryFor(Property property) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        property.roomTypes().forEach(roomType -> inventoryService.openInventory(roomType, today));
    }
}
