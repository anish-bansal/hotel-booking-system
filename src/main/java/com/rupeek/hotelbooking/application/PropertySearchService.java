package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.application.result.PropertySearchResult;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicyRegistry;
import com.rupeek.hotelbooking.domain.policy.PricingStrategy;
import com.rupeek.hotelbooking.domain.port.PropertyRepository;
import com.rupeek.hotelbooking.domain.search.PropertyFilter;
import com.rupeek.hotelbooking.domain.search.PropertySearchCriteria;
import com.rupeek.hotelbooking.domain.search.RoomTypeFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Property discovery: what can this guest actually book, for these dates, at this price?
 *
 * <h2>A four-stage pipeline, cheapest work first</h2>
 *
 * <ol>
 *   <li><b>Locate</b> — the datastore returns candidates in the requested city (and locality if
 *       given). This is the only stage pushed into a query, because location is the criterion that
 *       eliminates the most rows for the least effort.
 *   <li><b>Filter</b> — the injected {@link PropertyFilter} chain narrows on price, amenities,
 *       rating, and anything added later. These are cheap in-memory predicates over an
 *       already-small set. A second chain, {@link RoomTypeFilter}, then narrows the rooms
 *       <em>within</em> each surviving property — a hotel qualifying on its cheapest room must not
 *       go on to advertise its most expensive one.
 *   <li><b>Availability</b> — one batched query establishes the lowest nightly availability for
 *       every surviving room type. Deliberately last, because it is the most expensive stage and
 *       there is no sense checking inventory for a hotel the guest has already ruled out on price.
 *   <li><b>Quote</b> — surviving options are priced through the same {@link PricingStrategy} the
 *       booking path uses, so the search price and the checkout price cannot diverge.
 * </ol>
 *
 * <h2>Why the filters are injected</h2>
 *
 * This class never names a filter. It holds a {@code List<PropertyFilter>} that Spring populates
 * from whatever filter beans exist, so a new filter — "must have EV charging", "distance from a
 * landmark" — is a new class and a bean declaration. This file does not change, which is what the
 * brief means by adding filters without reworking the search.
 */
@Service
public class PropertySearchService {

    private static final Logger log = LoggerFactory.getLogger(PropertySearchService.class);

    private final PropertyRepository propertyRepository;
    private final InventoryService inventoryService;
    private final PricingStrategy pricingStrategy;
    private final CancellationPolicyRegistry cancellationPolicies;
    private final List<PropertyFilter> filters;
    private final List<RoomTypeFilter> roomTypeFilters;

    public PropertySearchService(PropertyRepository propertyRepository,
                                InventoryService inventoryService,
                                PricingStrategy pricingStrategy,
                                CancellationPolicyRegistry cancellationPolicies,
                                List<PropertyFilter> filters,
                                List<RoomTypeFilter> roomTypeFilters) {
        this.propertyRepository = propertyRepository;
        this.inventoryService = inventoryService;
        this.pricingStrategy = pricingStrategy;
        this.cancellationPolicies = cancellationPolicies;
        this.filters = List.copyOf(filters);
        this.roomTypeFilters = List.copyOf(roomTypeFilters);
        log.info("Property search active with {} property filter(s): {} and {} room-type filter(s): {}",
                filters.size(), filters.stream().map(PropertyFilter::name).toList(),
                roomTypeFilters.size(), roomTypeFilters.stream().map(RoomTypeFilter::name).toList());
    }

    @Transactional(readOnly = true)
    public List<PropertySearchResult> search(PropertySearchCriteria criteria) {
        List<Property> candidates = propertyRepository.findByLocation(criteria.city(), criteria.locality());

        List<Property> matching = candidates.stream()
                .filter(property -> satisfiesAllFilters(property, criteria))
                .toList();

        List<UUID> roomTypeIds = matching.stream()
                .flatMap(property -> property.roomTypes().stream())
                .map(RoomType::id)
                .toList();

        Map<UUID, Integer> availability =
                inventoryService.lowestAvailabilityPerRoomType(roomTypeIds, criteria.stay());

        List<PropertySearchResult> results = new ArrayList<>();
        for (Property property : matching) {
            List<PropertySearchResult.RoomTypeOption> options =
                    bookableOptions(property, criteria, availability);
            // A property with no option that fits the party for every night is not a result. The
            // brief is explicit that search must return only genuinely available properties.
            if (!options.isEmpty()) {
                results.add(toResult(property, options));
            }
        }

        log.debug("Search in {} matched {}/{} properties, {} genuinely available",
                criteria.city(), matching.size(), candidates.size(), results.size());
        return results;
    }

    private boolean satisfiesAllFilters(Property property, PropertySearchCriteria criteria) {
        return filters.stream()
                .filter(filter -> filter.isApplicable(criteria))
                .allMatch(filter -> filter.matches(property, criteria));
    }

    private boolean satisfiesAllRoomTypeFilters(RoomType roomType, PropertySearchCriteria criteria) {
        return roomTypeFilters.stream()
                .filter(filter -> filter.isApplicable(criteria))
                .allMatch(filter -> filter.matches(roomType, criteria));
    }

    private List<PropertySearchResult.RoomTypeOption> bookableOptions(
            Property property, PropertySearchCriteria criteria, Map<UUID, Integer> availability) {

        List<PropertySearchResult.RoomTypeOption> options = new ArrayList<>();
        for (RoomType roomType : property.roomTypes()) {
            // A property qualifying on its cheapest room does not make every room it owns
            // qualify. Each room type is judged on its own before it is offered to the guest.
            if (!satisfiesAllRoomTypeFilters(roomType, criteria)) {
                continue;
            }
            int roomsNeeded = roomType.roomsNeededFor(criteria.guests());
            int roomsAvailable = availability.getOrDefault(roomType.id(), 0);
            if (roomsAvailable < roomsNeeded) {
                continue;
            }
            options.add(new PropertySearchResult.RoomTypeOption(
                    roomType.id(),
                    roomType.name(),
                    roomType.maxOccupancy(),
                    roomsNeeded,
                    roomsAvailable,
                    roomType.basePricePerNight(),
                    pricingStrategy.quote(new PricingStrategy.PricingRequest(
                            roomType, criteria.stay(), roomsNeeded, criteria.guests()))));
        }
        return options;
    }

    private PropertySearchResult toResult(Property property,
                                          List<PropertySearchResult.RoomTypeOption> options) {
        return new PropertySearchResult(
                property.id(),
                property.name(),
                property.location().city(),
                property.location().locality(),
                property.starRating(),
                property.amenities(),
                cancellationPolicies.resolve(property.cancellationPolicyCode()).description(),
                options);
    }
}
