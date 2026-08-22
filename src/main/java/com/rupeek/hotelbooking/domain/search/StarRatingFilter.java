package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.Property;

/** Keeps properties rated at or above the requested number of stars. */
public class StarRatingFilter implements PropertyFilter {

    @Override
    public boolean isApplicable(PropertySearchCriteria criteria) {
        return criteria.minStarRating() != null;
    }

    @Override
    public boolean matches(Property property, PropertySearchCriteria criteria) {
        return property.starRating() >= criteria.minStarRating();
    }
}
