package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.Property;

/**
 * Keeps properties offering <em>every</em> amenity the guest asked for.
 *
 * <p>AND rather than OR: someone who ticks both "pool" and "pet friendly" is stating two
 * requirements, not offering two alternatives.
 */
public class AmenityFilter implements PropertyFilter {

    @Override
    public boolean isApplicable(PropertySearchCriteria criteria) {
        return !criteria.requiredAmenities().isEmpty();
    }

    @Override
    public boolean matches(Property property, PropertySearchCriteria criteria) {
        return property.hasAllAmenities(criteria.requiredAmenities());
    }
}
